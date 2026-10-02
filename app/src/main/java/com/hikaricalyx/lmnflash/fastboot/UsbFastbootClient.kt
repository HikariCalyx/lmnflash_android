package com.hikaricalyx.lmnflash.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.nio.charset.StandardCharsets

private const val FASTBOOT_CLASS = 0xff
private const val FASTBOOT_SUBCLASS = 0x42
private const val FASTBOOT_PROTOCOL = 0x03
private const val WRITE_TIMEOUT_MS = 5_000
private const val READ_PACKET_TIMEOUT_MS = 5_000
private const val COMMAND_TIMEOUT_MS = 20_000
private const val UNLOCK_TIMEOUT_MS = 90_000
private const val MAX_RESPONSE_PACKETS = 256
private const val FASTBOOT_PACKET_SIZE = 64

data class FastbootCandidate(val device: UsbDevice, val interfaceIndex: Int, val label: String)

enum class FastbootPlatform { QUALCOMM, MEDIATEK, UNKNOWN }

sealed interface FastbootUnlockResult {
    data object Unlocked : FastbootUnlockResult
    data object AlreadyUnlocked : FastbootUnlockResult
    data object OemUnlockingDisabled : FastbootUnlockResult
    data object Cancelled : FastbootUnlockResult
    data object WrongKey : FastbootUnlockResult
    data class Failed(val message: String) : FastbootUnlockResult
}

class UsbFastbootClient(private val usbManager: UsbManager) {
    fun candidates(): List<FastbootCandidate> = usbManager.deviceList.values.mapNotNull { device ->
        val interfaceIndex = (0 until device.interfaceCount).firstOrNull { index ->
            val iface = device.getInterface(index)
            iface.interfaceClass == FASTBOOT_CLASS && iface.interfaceSubclass == FASTBOOT_SUBCLASS && iface.interfaceProtocol == FASTBOOT_PROTOCOL && endpoints(iface) != null
        } ?: return@mapNotNull null
        val name = device.productName?.takeIf(String::isNotBlank) ?: "Fastboot device ${device.deviceId}"
        FastbootCandidate(device, interfaceIndex, name)
    }

    fun readUnlockData(candidate: FastbootCandidate): String = withSession(candidate) { session ->
        session.requireMotorola()
        val reply = session.oem("get_unlock_data")
        val lines = reply.successLinesOrThrow()
        parseUnlockData(lines)
    }

    fun unlock(candidate: FastbootCandidate, key: String): FastbootUnlockResult = withSession(candidate) { session ->
        val variables = session.requireMotorola()
        val platform = detectPlatform(variables)
        classifyUnlock(session.oem("unlock ${key.trim()}", UNLOCK_TIMEOUT_MS), platform)
    }

    private fun <T> withSession(candidate: FastbootCandidate, block: (Session) -> T): T {
        val iface = candidate.device.getInterface(candidate.interfaceIndex)
        val (out, input) = endpoints(iface) ?: error("Fastboot USB endpoints were not found")
        val connection = usbManager.openDevice(candidate.device) ?: error("Unable to open the Fastboot USB device")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            error("Unable to claim the Fastboot USB interface")
        }
        return try { block(Session(connection, iface, out, input)) } finally {
            connection.releaseInterface(iface)
            connection.close()
        }
    }

    private fun endpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var out: UsbEndpoint? = null
        var input: UsbEndpoint? = null
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) out = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
        }
        return if (out != null && input != null) out to input else null
    }
}

private class Session(
    private val connection: UsbDeviceConnection,
    @Suppress("unused") private val iface: UsbInterface,
    private val out: UsbEndpoint,
    private val input: UsbEndpoint,
) {
    fun requireMotorola(): Map<String, String> {
        val variables = parseGetvarAll(command("getvar:all").successLinesOrThrow())
        if (variables["cid"].isNullOrBlank()) error("No supported Motorola device found")
        return variables
    }

    fun oem(command: String, timeoutMs: Int = COMMAND_TIMEOUT_MS): Reply = command("oem $command", timeoutMs)

    private fun command(command: String, timeoutMs: Int = COMMAND_TIMEOUT_MS): Reply {
        write(command.toByteArray(StandardCharsets.US_ASCII))
        val info = mutableListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        repeat(MAX_RESPONSE_PACKETS) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) error("Fastboot command timed out waiting for a response")
            val packet = read(minOf(READ_PACKET_TIMEOUT_MS.toLong(), remaining).toInt())
            when (packet.header) {
                "INFO" -> packet.payload.takeIf(String::isNotBlank)?.let(info::add)
                "OKAY" -> {
                    packet.payload.takeIf(String::isNotBlank)?.let(info::add)
                    return Reply.Success(info)
                }
                "FAIL" -> return Reply.Failure(packet.payload, info)
                else -> error("Unexpected Fastboot response: ${packet.header}")
            }
        }
        error("Fastboot command returned too many response packets")
    }

    private fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = connection.bulkTransfer(out, bytes, offset, bytes.size - offset, WRITE_TIMEOUT_MS)
            if (count <= 0) error("Fastboot USB write timed out")
            offset += count
        }
    }

    private fun read(timeoutMs: Int): Packet {
        val buffer = ByteArray(FASTBOOT_PACKET_SIZE)
        val count = connection.bulkTransfer(input, buffer, 0, buffer.size, timeoutMs)
        if (count < 4) error(if (count < 0) "Fastboot USB read timed out" else "Short Fastboot response")
        return Packet(
            String(buffer, 0, 4, StandardCharsets.US_ASCII),
            String(buffer, 4, count - 4, StandardCharsets.UTF_8).trimEnd('\u0000'),
        )
    }
}

private data class Packet(val header: String, val payload: String)
private sealed interface Reply {
    data class Success(val lines: List<String>) : Reply
    data class Failure(val message: String, val info: List<String>) : Reply
}
private fun Reply.successLinesOrThrow(): List<String> = when (this) {
    is Reply.Success -> lines
    is Reply.Failure -> error(listOf(message, info.joinToString(" | ")).filter(String::isNotBlank).joinToString(" | "))
}

private fun parseUnlockData(lines: List<String>): String {
    val data = lines.joinToString("") { line ->
        val trimmed = line.trim()
        if (trimmed.contains("failed to get unlock data", true)) error("Failed to get unlock data: the device did not return its Device ID.")
        val marker = "unlock data:"
        val markerIndex = trimmed.indexOf(marker, ignoreCase = true)
        if (markerIndex >= 0) trimmed.substring(markerIndex + marker.length) else trimmed
    }
    if (data.isBlank()) error("No unlock data returned by the device.")
    return data
}

private fun parseGetvarAll(lines: List<String>): Map<String, String> {
    val parts = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    lines.forEach { raw ->
        val line = raw.substringAfter("(bootloader)", raw).trim()
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val rawKey = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        val indexed = Regex("^(.*)\\[(\\d+)]$").matchEntire(rawKey)
        val key = indexed?.groupValues?.get(1) ?: rawKey
        val index = indexed?.groupValues?.get(2)?.toIntOrNull() ?: 0
        parts.getOrPut(key) { mutableListOf() } += index to value
    }
    return parts.mapValues { (_, values) -> values.sortedBy { it.first }.joinToString("") { it.second } }
}

private fun detectPlatform(variables: Map<String, String>): FastbootPlatform {
    val cpu = variables["cpu"].orEmpty().lowercase()
    val qualcomm = variables.keys.any { it.startsWith("ro.build.version.qcom") } || listOf("sm_", "msm", "apq", "sdm", "qcm", "sc7", "qsc", "snapdragon").any { cpu.startsWith(it) || cpu.contains(it) }
    val mediatek = variables.keys.any { it.contains("mediatek", true) } || cpu.contains("mediatek") || cpu.contains("dimensity") || cpu.contains("mtk") || (cpu.startsWith("mt") && cpu.getOrNull(2)?.isDigit() == true)
    return when { qualcomm && !mediatek -> FastbootPlatform.QUALCOMM; mediatek && !qualcomm -> FastbootPlatform.MEDIATEK; else -> FastbootPlatform.UNKNOWN }
}

private fun classifyUnlock(reply: Reply, platform: FastbootPlatform): FastbootUnlockResult {
    val text = when (reply) { is Reply.Success -> reply.lines.joinToString("\n"); is Reply.Failure -> listOf(reply.message, reply.info.joinToString("\n")).joinToString("\n") }.trim()
    val lower = text.lowercase()
    return when {
        lower.contains("oem unlocking") || lower.contains("allow oem unlock") -> FastbootUnlockResult.OemUnlockingDisabled
        lower.contains("code validation failure") -> FastbootUnlockResult.WrongKey
        lower.contains("already unlock") -> FastbootUnlockResult.AlreadyUnlocked
        text.isBlank() && (reply is Reply.Failure || platform == FastbootPlatform.MEDIATEK) -> FastbootUnlockResult.Cancelled
        reply is Reply.Success -> FastbootUnlockResult.Unlocked
        else -> FastbootUnlockResult.Failed(text.ifBlank { "Fastboot unlock failed" })
    }
}
