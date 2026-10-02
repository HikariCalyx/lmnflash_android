package com.hikaricalyx.lmnflash.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hikaricalyx.lmnflash.firmwareflash.FlashOperation
import com.hikaricalyx.lmnflash.firmwareflash.FlashPackage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

private const val FASTBOOT_CLASS = 0xff
private const val FASTBOOT_SUBCLASS = 0x42
private const val FASTBOOT_PROTOCOL = 0x03
private const val WRITE_TIMEOUT_MS = 20_000
private const val READ_PACKET_TIMEOUT_MS = 5_000
private const val COMMAND_TIMEOUT_MS = 20_000
private const val FLASH_TIMEOUT_MS = 120_000
private const val UNLOCK_TIMEOUT_MS = 90_000
// `oem config` on engineering Motorola bootloaders can emit hundreds of INFO packets.
// Keep a hard bound to protect the app from a malformed endpoint while allowing full diagnostics.
private const val MAX_RESPONSE_PACKETS = 4_096
private const val FASTBOOT_PACKET_SIZE = 64
private const val DEFAULT_MAX_DOWNLOAD_SIZE = 128L shl 20
private val SIDELOAD_BCB = ByteArray(84).apply {
    "boot-recovery".toByteArray(StandardCharsets.US_ASCII).copyInto(this, destinationOffset = 0)
    "recovery\n--sideload\n".toByteArray(StandardCharsets.US_ASCII).copyInto(this, destinationOffset = 64)
}

data class FastbootCandidate(
    val device: UsbDevice,
    val interfaceIndex: Int,
    val label: String,
    val source: DeviceReadSource = DeviceReadSource.FASTBOOT,
)

data class RetcnDeviceInfo(
    val imei: String? = null,
    val serialNumber: String? = null,
    val model: String? = null,
    val carrier: String? = null,
    val fingerprint: String? = null,
    val platform: FastbootPlatform = FastbootPlatform.UNKNOWN,
    val fsgVersion: String? = null,
    val simCount: Int? = null,
)

data class FastbootDeviceInfo(
    val serialNumber: String? = null,
    val xtModel: String? = null,
    val product: String? = null,
    val cid: String? = null,
    val secureState: String? = null,
    val currentSlot: String? = null,
    val userspace: String? = null,
)

enum class FastbootPlatform { QUALCOMM, MEDIATEK, UNKNOWN }

enum class FastbootRebootMode { NORMAL, FASTBOOTD, RECOVERY, ADB_SIDELOAD, SWITCH_SLOT }

sealed interface FastbootUnlockResult {
    data object Unlocked : FastbootUnlockResult
    data object AlreadyUnlocked : FastbootUnlockResult
    data object OemUnlockingDisabled : FastbootUnlockResult
    data object Cancelled : FastbootUnlockResult
    data object WrongKey : FastbootUnlockResult
    data class Failed(val message: String) : FastbootUnlockResult
}

class UsbFastbootClient(private val usbManager: UsbManager) {
    interface FlashCallbacks {
        fun onStep(index: Int, total: Int, label: String)
        fun onProgress(done: Long, total: Long)
        fun onLog(line: String)
    }

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
        parseUnlockData(session.oem("get_unlock_data").successLinesOrThrow())
    }

    fun readRetcnInfo(candidate: FastbootCandidate): RetcnDeviceInfo = withSession(candidate) { session ->
        val variables = session.requireMotorola().mapKeys { (key, _) -> key.lowercase() }
        val imei = variables.firstValue("imei")?.filter(Char::isDigit)?.takeIf { it.length in 14..15 }
        val model = variables.firstValue("sku", "ro.boot.hardware.sku", "ro.product.model", "model")?.takeIf { XT_MODEL.matches(it) }
        val platform = detectPlatform(variables)
        val simCount = if (platform == FastbootPlatform.MEDIATEK) runCatching { parseDualSim(session.oem("hw dualsim").successLinesOrThrow()) }.getOrNull() else null
        RetcnDeviceInfo(
            imei = imei,
            serialNumber = variables.firstValue("serialno", "serial-number"),
            model = model,
            carrier = variables.firstValue("ro.carrier", "carrier"),
            fingerprint = variables.firstValue("ro.build.fingerprint", "fingerprint", "ro.fingerprint"),
            platform = platform,
            fsgVersion = variables.fsgVersion(),
            simCount = simCount,
        )
    }

    fun readFlashDeviceInfo(candidate: FastbootCandidate): FastbootDeviceInfo = withSession(candidate) { session ->
        val variables = session.requireMotorola().mapKeys { (key, _) -> key.lowercase() }
        FastbootDeviceInfo(
            serialNumber = variables.firstValue("serialno", "serial-number"),
            xtModel = variables.firstValue("sku"),
            product = variables.firstValue("product"),
            cid = variables.firstValue("cid"),
            secureState = variables.firstValue("securestate", "secure-state"),
            currentSlot = variables.firstValue("current-slot", "slot"),
            userspace = variables.firstValue("is-userspace", "userspace"),
        )
    }

    /** Reads the same diagnostic commands offered by the PC Firmware Flash screen. */
    fun readFlashInfo(candidate: FastbootCandidate): List<String> = withSession(candidate) { session ->
        session.requireMotorola()
        INFO_COMMANDS.flatMapIndexed { index, (label, command) ->
            buildList {
                if (index > 0) add("")
                add("> $label")
                runCatching { command(session).successLinesOrThrow() }
                    .onSuccess { addAll(joinIndexedInfoLines(it)) }
                    .onFailure { add("failed: ${it.message ?: "Fastboot command failed"}") }
            }
        }
    }

    /** Sends a literal non-flash Fastboot command and returns its raw protocol packets. */
    fun executeCustomCommand(candidate: FastbootCandidate, command: String): List<String> = withSession(candidate) { session ->
        session.requireMotorola()
        session.rawCommand(command)
    }

    /** Streams a user-selected image through Fastboot download, then flashes the requested partition. */
    fun executeCustomFlash(candidate: FastbootCandidate, partition: String, input: InputStream, size: Long, onProgress: (Long, Long) -> Unit): List<String> = withSession(candidate) { session ->
        session.requireMotorola()
        val handshake = mutableListOf<String>()
        // Mirror the firmware flow: download() consumes the bootloader's post-download acknowledgement
        // before the flash command is sent, otherwise the two responses get out of sync.
        session.download(size, input, { done -> onProgress(done, size) }) { line -> handshake += line }
        handshake + session.rawCommand("flash:$partition", FLASH_TIMEOUT_MS)
    }

    /** Runs the supplied safe subset in original package order; it cannot add or alter commands. */
    fun flash(
        candidate: FastbootCandidate,
        flashPackage: FlashPackage,
        operations: List<FlashOperation>,
        callbacks: FlashCallbacks,
    ) = withSession(candidate) { session ->
        require(operations.isNotEmpty()) { "Select at least one flashing step." }
        session.requireMotorola()
        val maxDownload = session.maxDownloadSize(callbacks::onLog) ?: DEFAULT_MAX_DOWNLOAD_SIZE
        callbacks.onLog("max download size: $maxDownload bytes")
        operations.forEachIndexed { index, operation ->
            callbacks.onStep(index, operations.size, operation.label)
            when (operation) {
                is FlashOperation.Flash -> {
                    // Match the PC flow: validate this image directly before writing its partition.
                    flashPackage.verifyChecksum(operation, callbacks::onLog)
                    val size = flashPackage.imageSize(operation)
                    require(size <= maxDownload) {
                        "${operation.filename} is $size bytes, but this bootloader accepts at most $maxDownload bytes per download. This Android build cannot safely split this image."
                    }
                    flashPackage.openImage(operation).use { image ->
                        session.download(size, image, { done -> callbacks.onProgress(done, size) }, callbacks::onLog)
                    }
                    session.command("flash:${operation.partition}", FLASH_TIMEOUT_MS, callbacks::onLog).successLinesOrThrow()
                }
                is FlashOperation.Erase -> session.command("erase:${operation.partition}", FLASH_TIMEOUT_MS, callbacks::onLog).successLinesOrThrow()
                is FlashOperation.Oem -> session.command("oem ${operation.command}", FLASH_TIMEOUT_MS, callbacks::onLog).successLinesOrThrow()
                is FlashOperation.Getvar -> runCatching { session.command("getvar:${operation.variable}", COMMAND_TIMEOUT_MS, callbacks::onLog).successLinesOrThrow() }
                    .onFailure { callbacks.onLog("getvar ${operation.variable} skipped: ${it.message}") }
            }
        }
        runCatching { session.command("set_active:a", COMMAND_TIMEOUT_MS, callbacks::onLog).successLinesOrThrow() }
            .onFailure { callbacks.onLog("set active slot a skipped: ${it.message}") }
    }

    fun reboot(candidate: FastbootCandidate, mode: FastbootRebootMode = FastbootRebootMode.NORMAL) = withSession(candidate) { session ->
        session.requireMotorola()
        when (mode) {
            FastbootRebootMode.NORMAL -> {
                session.oem("fb_mode_clear").successLinesOrThrow()
                session.rebootTolerantly("reboot")
            }
            FastbootRebootMode.FASTBOOTD -> session.rebootTolerantly("reboot:fastboot")
            FastbootRebootMode.RECOVERY -> session.rebootTolerantly("reboot:recovery")
            FastbootRebootMode.ADB_SIDELOAD -> {
                session.flashBytes("misc", SIDELOAD_BCB)
                session.rebootTolerantly("reboot")
            }
            FastbootRebootMode.SWITCH_SLOT -> {
                session.oem("fb_mode_clear").successLinesOrThrow()
                val current = session.getvarValue("current-slot").trim().removePrefix("_").lowercase()
                val other = when (current) {
                    "a" -> "b"
                    "b" -> "a"
                    else -> error("The device did not report an A/B current slot.")
                }
                session.command("set_active:$other", COMMAND_TIMEOUT_MS).successLinesOrThrow()
                session.rebootTolerantly("reboot")
            }
        }
    }

    fun unlock(candidate: FastbootCandidate, key: String): FastbootUnlockResult = withSession(candidate) { session ->
        val variables = session.requireMotorola()
        classifyUnlock(session.oem("unlock ${key.trim()}", UNLOCK_TIMEOUT_MS), detectPlatform(variables))
    }

    private fun <T> withSession(candidate: FastbootCandidate, block: (Session) -> T): T {
        val iface = candidate.device.getInterface(candidate.interfaceIndex)
        val (out, input) = endpoints(iface) ?: error("Fastboot USB endpoints were not found")
        val connection = usbManager.openDevice(candidate.device) ?: error("Unable to open the Fastboot USB device")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            error("Unable to claim the Fastboot USB interface")
        }
        return try { block(Session(connection, out, input)) } finally {
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

private val INFO_COMMANDS: List<Pair<String, (Session) -> Reply>> = listOf(
    "getvar all" to { session -> session.command("getvar:all") },
    "oem hw" to { session -> session.oem("hw") },
    "oem build-signature" to { session -> session.oem("build-signature") },
    "oem read_sv" to { session -> session.oem("read_sv") },
    "oem config" to { session -> session.oem("config") },
)

private data class IndexedInfoLine(val prefix: String, val base: String, val index: Int, val value: String)

private fun joinIndexedInfoLines(lines: List<String>): List<String> {
    val parsed = lines.map { line ->
        val match = INDEXED_INFO_LINE.matchEntire(line.trim()) ?: return@map null
        IndexedInfoLine(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt(), match.groupValues[4])
    }
    val groups = parsed.filterNotNull().groupBy { it.base }
    return buildList {
        lines.indices.forEach { position ->
            val part = parsed[position]
            if (part == null) {
                add(lines[position])
            } else if (part.index == groups.getValue(part.base).minOf { it.index }) {
                val joined = groups.getValue(part.base).sortedBy { it.index }.joinToString("") { it.value }
                add("${part.prefix}${part.base}: $joined".trimEnd())
            }
        }
    }
}

private val INDEXED_INFO_LINE = Regex("^(\\(bootloader\\)\\s*)?(.*?)\\[(\\d+)]\\s*:\\s*(.*)$")

fun maskFastbootInfo(lines: List<String>): List<String> = lines.map { line ->
    val separator = line.indexOf(':')
    if (separator <= 0) return@map line
    val label = line.substring(0, separator).lowercase()
    val value = line.substring(separator + 1)
    if ('/' in label || value.trim().any { !it.isDigit() }) return@map line
    val visible = when {
        "imei" in label -> 4
        "iccid" in label || "esimid" in label -> 6
        else -> return@map line
    }
    val trimmed = value.trim()
    if (trimmed.length <= visible) return@map line
    val indent = value.takeWhile(Char::isWhitespace)
    "${line.substring(0, separator)}:$indent${"x".repeat(trimmed.length - visible)}${trimmed.takeLast(visible)}"
}

private class Session(
    private val connection: UsbDeviceConnection,
    private val out: UsbEndpoint,
    private val input: UsbEndpoint,
) {
    fun requireMotorola(): Map<String, String> {
        val variables = parseGetvarAll(command("getvar:all").successLinesOrThrow())
        if (variables["cid"].isNullOrBlank()) error("No supported Motorola device found")
        return variables
    }

    fun oem(command: String, timeoutMs: Int = COMMAND_TIMEOUT_MS): Reply = command("oem $command", timeoutMs)

    fun getvarValue(name: String): String {
        val lines = command("getvar:$name", COMMAND_TIMEOUT_MS).successLinesOrThrow()
        val line = lines.asReversed().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val body = line.removePrefix("(bootloader)").trim()
        return body.substringAfter("$name:", body).trim()
    }

    fun flashBytes(partition: String, bytes: ByteArray) {
        download(bytes.size.toLong(), ByteArrayInputStream(bytes), progress = {}, log = {})
        command("flash:$partition", FLASH_TIMEOUT_MS).successLinesOrThrow()
    }

    fun rebootTolerantly(command: String) {
        runCatching { this.command(command, COMMAND_TIMEOUT_MS).successLinesOrThrow() }
            .onFailure { error ->
                // A successful reboot may disconnect USB before its final OKAY packet is read.
                if (error.message !in setOf("Fastboot USB read timed out", "Fastboot command timed out waiting for a response")) throw error
            }
    }

    fun maxDownloadSize(log: (String) -> Unit): Long? = runCatching {
        command("getvar:max-download-size", COMMAND_TIMEOUT_MS, log).successLinesOrThrow()
            .asSequence().map { it.substringAfterLast(':').trim() }
            .mapNotNull { value -> value.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: value.toLongOrNull() }
            .firstOrNull()
    }.getOrNull()

    fun download(size: Long, inputStream: InputStream, progress: (Long) -> Unit, log: (String) -> Unit) {
        require(size in 1..0xffff_ffffL) { "Image size is not supported by Fastboot: $size" }
        write("download:%08x".format(size).toByteArray(StandardCharsets.US_ASCII))
        val announced = readDataResponse(COMMAND_TIMEOUT_MS, log)
        require(announced == size) { "Fastboot accepted $announced bytes for a $size byte image." }
        val buffer = ByteArray(1 shl 20)
        var sent = 0L
        while (sent < size) {
            val read = inputStream.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
            if (read <= 0) error("Firmware image ended before $size bytes were sent")
            write(buffer, 0, read)
            sent += read
            progress(sent)
        }
        commandResponse(FLASH_TIMEOUT_MS, log).successLinesOrThrow()
    }

    fun command(command: String, timeoutMs: Int = COMMAND_TIMEOUT_MS, log: (String) -> Unit = {}): Reply {
        write(command.toByteArray(StandardCharsets.US_ASCII))
        return commandResponse(timeoutMs, log)
    }

    fun rawCommand(command: String, timeoutMs: Int = COMMAND_TIMEOUT_MS): List<String> {
        write(command.toByteArray(StandardCharsets.US_ASCII))
        val packets = mutableListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        repeat(MAX_RESPONSE_PACKETS) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) error("Fastboot command timed out waiting for a response")
            val packet = read(minOf(READ_PACKET_TIMEOUT_MS.toLong(), remaining).toInt())
            packets += packet.header + packet.payload
            when (packet.header) {
                "INFO" -> Unit
                "OKAY", "FAIL" -> return packets
                else -> error("Unexpected Fastboot response: ${packet.header}")
            }
        }
        error("Fastboot command returned too many response packets")
    }

    private fun readDataResponse(timeoutMs: Int, log: (String) -> Unit): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        repeat(MAX_RESPONSE_PACKETS) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) error("Fastboot command timed out waiting for a response")
            val packet = read(minOf(READ_PACKET_TIMEOUT_MS.toLong(), remaining).toInt())
            when (packet.header) {
                "INFO" -> packet.payload.takeIf(String::isNotBlank)?.let(log)
                "DATA" -> return packet.payload.toLongOrNull(16) ?: error("Invalid Fastboot DATA response: ${packet.payload}")
                "FAIL" -> error(packet.payload.ifBlank { "Fastboot download was rejected" })
                else -> error("Unexpected Fastboot response: ${packet.header}")
            }
        }
        error("Fastboot command returned too many response packets")
    }

    private fun commandResponse(timeoutMs: Int, log: (String) -> Unit): Reply {
        val info = mutableListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        repeat(MAX_RESPONSE_PACKETS) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) error("Fastboot command timed out waiting for a response")
            val packet = read(minOf(READ_PACKET_TIMEOUT_MS.toLong(), remaining).toInt())
            when (packet.header) {
                "INFO" -> packet.payload.takeIf(String::isNotBlank)?.let { info += it; log(it) }
                "OKAY" -> {
                    packet.payload.takeIf(String::isNotBlank)?.let { info += it; log(it) }
                    return Reply.Success(info)
                }
                "FAIL" -> return Reply.Failure(packet.payload, info)
                else -> error("Unexpected Fastboot response: ${packet.header}")
            }
        }
        error("Fastboot command returned too many response packets")
    }

    private fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = connection.bulkTransfer(out, bytes, position, end - position, WRITE_TIMEOUT_MS)
            if (count <= 0) error("Fastboot USB write timed out")
            position += count
        }
    }

    private fun read(timeoutMs: Int): Packet {
        val buffer = ByteArray(FASTBOOT_PACKET_SIZE)
        val count = connection.bulkTransfer(input, buffer, 0, buffer.size, timeoutMs)
        if (count < 4) error(if (count < 0) "Fastboot USB read timed out" else "Short Fastboot response")
        return Packet(String(buffer, 0, 4, StandardCharsets.US_ASCII), String(buffer, 4, count - 4, StandardCharsets.UTF_8).trimEnd('\u0000'))
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

private val XT_MODEL = Regex("^XT[A-Z0-9-]+$", RegexOption.IGNORE_CASE)
private fun Map<String, String>.firstValue(vararg keys: String): String? = keys.asSequence().mapNotNull { key -> this[key]?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
private fun Map<String, String>.fsgVersion(): String? {
    firstValue("fsg-id", "fsg-version", "fsgid", "fsgversion", "fsg-version.qcom", "fsgversion.qcom")?.let { return it }
    entries.firstOrNull { (key, value) -> key.contains("fsg", ignoreCase = true) && value.isNotBlank() }?.value?.trim()?.let { return it }
    return this["version-baseband"]?.substringAfter(' ', "")?.trim()?.takeIf(String::isNotBlank)
}
private fun parseDualSim(lines: List<String>): Int? {
    val text = lines.joinToString("\n").lowercase()
    if ("true" in text) return 2
    if ("false" in text) return 1
    return lines.asSequence().flatMap { it.split(':', '=', ' ').asSequence() }.map(String::trim).mapNotNull { token -> if (token.startsWith("0x", true)) token.drop(2).toIntOrNull(16) else token.toIntOrNull() }.firstOrNull { it == 1 || it == 2 }
}
