package com.hikaricalyx.lmnflash.fastboot

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.math.min

private const val ADB_CLASS = 0xff
private const val ADB_SUBCLASS = 0x42
private const val ADB_PROTOCOL = 0x01
// Use the checksum-enabled revision; 0x01000001 explicitly permits peers to omit data checksums.
private const val ADB_VERSION = 0x01000000
private const val ADB_MAX_DATA = 4 * 1024
private const val ADB_MAX_PACKET_DATA = 1024 * 1024
private const val ADB_WRITE_TIMEOUT_MS = 5_000
private const val ADB_READ_TIMEOUT_MS = 5_000
private const val ADB_COMMAND_TIMEOUT_MS = 30_000
private const val ADB_AUTH_RESPONSE_TIMEOUT_MS = 30_000
// Allows a receive pass after AUTH_RSAPUBLICKEY while the user responds on the device.
private const val ADB_AUTH_ATTEMPTS = 4

/** Raw ADB-over-USB client used only for reading non-sensitive RETCN lookup properties. */
class UsbAdbClient(
    private val context: Context,
    private val usbManager: UsbManager,
) {
    fun candidates(): List<FastbootCandidate> = usbManager.deviceList.values.mapNotNull { device ->
        val interfaceIndex = (0 until device.interfaceCount).firstOrNull { index ->
            val iface = device.getInterface(index)
            iface.interfaceClass == ADB_CLASS &&
                iface.interfaceSubclass == ADB_SUBCLASS &&
                iface.interfaceProtocol == ADB_PROTOCOL &&
                endpoints(iface) != null
        } ?: return@mapNotNull null
        val name = device.productName?.takeIf(String::isNotBlank) ?: "ADB device ${device.deviceId}"
        FastbootCandidate(device, interfaceIndex, name, DeviceReadSource.ADB_USB)
    }

    /** A single authorization attempt; Retry is driven by the UI after this returns pending. */
    fun readRetcnInfo(candidate: FastbootCandidate): RetcnDeviceInfo = try {
        withSession(candidate, ::readRetcnInfo)
    } catch (error: AdbAuthorizationPendingException) {
        throw error
    } catch (error: AdbTransportException) {
        throw error
    } catch (error: Throwable) {
        throw AdbTransportException(error.message ?: "Unknown ADB USB failure", error)
    }

    private fun readRetcnInfo(session: AdbSession): RetcnDeviceInfo {
        val properties = parseProperties(session.shell("getprop"))
        val platform = detectPlatform(properties)
        return RetcnDeviceInfo(
            // Production adbd does not provide a supported, non-privileged IMEI API.
            serialNumber = properties.firstValue("ro.serialno", "ro.boot.serialno", "ro.boot.hardware.serialno"),
            model = properties.firstValue("ro.boot.hardware.sku", "ro.product.vendor.model", "ro.product.model")
                ?.takeIf { ADB_XT_MODEL.matches(it) },
            carrier = properties.firstValue("ro.carrier"),
            fingerprint = properties.firstValue("ro.build.fingerprint"),
            platform = platform,
            fsgVersion = properties.firstValue("vendor.ril.baseband.config.version", "persist.radio.baseband.config.version"),
            simCount = properties.firstValue("persist.radio.multisim.config", "ro.telephony.default_network")
                ?.let(::simCount),
        )
    }

    private fun <T> withSession(candidate: FastbootCandidate, block: (AdbSession) -> T): T {
        val iface = candidate.device.getInterface(candidate.interfaceIndex)
        val (out, input) = endpoints(iface) ?: error("ADB USB endpoints were not found")
        val connection = usbManager.openDevice(candidate.device) ?: error("Unable to open the ADB USB device")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            error("Unable to claim the ADB USB interface")
        }
        return try {
            AdbSession(context, connection, out, input).use(block)
        } finally {
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

private class AdbSession(
    private val context: Context,
    private val connection: UsbDeviceConnection,
    private val out: UsbEndpoint,
    private val input: UsbEndpoint,
) : AutoCloseable {
    private var authenticated = false
    /** A USB bulk transfer can contain an ADB header plus payload (or multiple packets). */
    private val receiveBuffer = ByteArray(16 * 1024)
    private var bufferedBytes = ByteArray(0)
    private var bufferedOffset = 0

    fun shell(command: String): String {
        authenticate()
        val localId = 1
        write(AdbPacket(ADB_OPEN, localId, 0, "shell:$command\u0000".toByteArray()))
        var remoteId = 0
        val output = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + ADB_COMMAND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val packet = readPacket(remainingTimeout(deadline))) {
                is AdbPacket -> when (packet.command) {
                    ADB_OKAY -> if (packet.arg1 == localId) remoteId = packet.arg0
                    ADB_WRTE -> if (packet.arg1 == localId) {
                        output.append(packet.payload.toString(Charsets.UTF_8))
                        write(AdbPacket(ADB_OKAY, localId, packet.arg0, ByteArray(0)))
                    }
                    ADB_CLSE -> if (packet.arg1 == localId) {
                        write(AdbPacket(ADB_CLSE, localId, packet.arg0, ByteArray(0)))
                        return output.toString()
                    }
                }
            }
        }
        error("ADB shell command timed out")
    }

    private fun authenticate() {
        if (authenticated) return
        write(AdbPacket(ADB_CNXN, ADB_VERSION, ADB_MAX_DATA, "host::\u0000".toByteArray()))
        var sentSignature = false
        var sentPublicKey = false
        repeat(ADB_AUTH_ATTEMPTS) {
            val packet = try {
                readPacket(ADB_AUTH_RESPONSE_TIMEOUT_MS)
            } catch (error: AdbUsbReadTimeoutException) {
                if (sentPublicKey) throw AdbAuthorizationPendingException()
                throw error
            }
            when (packet.command) {
                ADB_CNXN -> {
                    authenticated = true
                    return
                }
                ADB_AUTH -> {
                    if (packet.arg0 != ADB_AUTH_TOKEN) error("ADB authentication protocol failed")
                    if (!sentSignature) {
                        write(AdbPacket(ADB_AUTH, ADB_AUTH_SIGNATURE, 0, AdbHostKey.sign(context, packet.payload)))
                        sentSignature = true
                    } else if (!sentPublicKey) {
                        write(AdbPacket(ADB_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, AdbHostKey.publicKey(context)))
                        sentPublicKey = true
                    } else {
                        // The key is already submitted; keep this transport alive for the user's response.
                    }
                }
                ADB_STLS -> error("This ADB device requires TLS, which is not supported over USB")
                else -> error("Unexpected ADB response during authentication")
            }
        }
        if (sentPublicKey) throw AdbAuthorizationPendingException()
        error("ADB authorization was not granted. Accept the USB debugging prompt on the device and try again.")
    }

    private fun write(packet: AdbPacket) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(packet.command)
            putInt(packet.arg0)
            putInt(packet.arg1)
            putInt(packet.payload.size)
            putInt(packet.payload.sumOf { it.toInt() and 0xff })
            putInt(packet.command.inv())
        }.array()
        writeFully(header)
        if (packet.payload.isNotEmpty()) writeFully(packet.payload)
    }

    private fun readPacket(timeoutMs: Int): AdbPacket {
        val header = readFully(24, timeoutMs)
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = values.int
        val arg0 = values.int
        val arg1 = values.int
        val length = values.int
        val checksum = values.int
        val magic = values.int
        if (magic != command.inv()) error("Invalid ADB packet header")
        if (length !in 0..ADB_MAX_PACKET_DATA) error("Invalid ADB packet size")
        val payload = if (length == 0) ByteArray(0) else readFully(length, timeoutMs)
        // ADB's skip-checksum revision uses zero here. Legacy peers provide the byte sum.
        if (checksum != 0 && payload.sumOf { it.toInt() and 0xff } != checksum) error("Invalid ADB packet checksum")
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun writeFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = connection.bulkTransfer(out, bytes, offset, bytes.size - offset, ADB_WRITE_TIMEOUT_MS)
            if (written <= 0) error("ADB USB write timed out")
            offset += written
        }
    }

    private fun readFully(size: Int, timeoutMs: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (offset < size) {
            val buffered = bufferedBytes.size - bufferedOffset
            if (buffered > 0) {
                val copied = minOf(size - offset, buffered)
                bufferedBytes.copyInto(result, offset, bufferedOffset, bufferedOffset + copied)
                bufferedOffset += copied
                offset += copied
                continue
            }
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
            val read = connection.bulkTransfer(input, receiveBuffer, 0, receiveBuffer.size, min(ADB_READ_TIMEOUT_MS, remaining))
            if (read <= 0) throw AdbUsbReadTimeoutException()
            bufferedBytes = receiveBuffer.copyOf(read)
            bufferedOffset = 0
        }
        return result
    }

    private fun remainingTimeout(deadline: Long): Int =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1).coerceAtMost(ADB_READ_TIMEOUT_MS.toLong()).toInt()

    override fun close() = Unit
}

private object AdbHostKey {
    private const val ALIAS = "lmnflash_adb_host_key"

    fun sign(context: Context, token: ByteArray): ByteArray {
        val entry = keyEntry(context)
        return Signature.getInstance("NONEwithRSA").run {
            initSign(entry.privateKey)
            update(token)
            sign()
        }
    }

    fun publicKey(context: Context): ByteArray {
        val publicKey = keyEntry(context).certificate.publicKey as? RSAPublicKey
            ?: error("Unable to read the ADB host public key")
        return (Base64.encodeToString(adbPublicKey(publicKey), Base64.NO_WRAP) + " LMN Flash\n\u0000").toByteArray()
    }

    private fun keyEntry(context: Context): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build(),
            )
            generateKeyPair()
        }
        keyStore.load(null)
        return keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Unable to create the ADB host key")
    }

    /** Encodes Android's RSAPublicKey structure used by adbd for AUTH_RSAPUBLICKEY. */
    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val wordCount = 64
        val modulus = key.modulus
        val base = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.and(base - BigInteger.ONE)
        val n0inv = base.subtract(n0.modInverse(base)).and(base - BigInteger.ONE)
        val rr = BigInteger.ONE.shiftLeft(32 * wordCount).modPow(BigInteger.TWO, modulus)
        return ByteBuffer.allocate(4 + 4 + wordCount * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(wordCount)
            putInt(n0inv.toInt())
            put(littleEndian(modulus, wordCount * 4))
            put(littleEndian(rr, wordCount * 4))
            putInt(key.publicExponent.toInt())
        }.array()
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val unsigned = value.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        require(unsigned.size <= size)
        return ByteArray(size).also { output ->
            unsigned.reversedArray().copyInto(output)
        }
    }
}

class AdbAuthorizationPendingException : Exception()

class AdbTransportException(detail: String, cause: Throwable? = null) : Exception(detail, cause)

private class AdbUsbReadTimeoutException : IllegalStateException("ADB USB read timed out")

private data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

private fun parseProperties(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
    val match = PROPERTY_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
    match.groupValues[1] to match.groupValues[2].trim()
}.toMap()

private fun detectPlatform(properties: Map<String, String>): FastbootPlatform {
    val hardware = listOf("ro.board.platform", "ro.hardware", "ro.boot.hardware").mapNotNull(properties::get).joinToString(" ").lowercase()
    val qualcomm = listOf("sm", "msm", "apq", "sdm", "qcm", "snapdragon", "qsc").any { hardware.contains(it) }
    val mediatek = listOf("mediatek", "dimensity", "mtk").any { hardware.contains(it) } || Regex("\\bmt\\d").containsMatchIn(hardware)
    return when {
        qualcomm && !mediatek -> FastbootPlatform.QUALCOMM
        mediatek && !qualcomm -> FastbootPlatform.MEDIATEK
        else -> FastbootPlatform.UNKNOWN
    }
}

private fun simCount(value: String): Int? = when {
    value.contains("dsds", true) || value.contains("dsda", true) || value.contains("tsts", true) -> 2
    value.contains("ss", true) -> 1
    else -> null
}

private fun Map<String, String>.firstValue(vararg names: String): String? = names.asSequence()
    .mapNotNull { this[it]?.trim()?.takeIf(String::isNotBlank) }
    .firstOrNull()

private val ADB_XT_MODEL = Regex("^XT[A-Z0-9-]+$", RegexOption.IGNORE_CASE)
private val PROPERTY_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
private const val ADB_CNXN = 0x4e584e43
private const val ADB_AUTH = 0x48545541
private const val ADB_OPEN = 0x4e45504f
private const val ADB_OKAY = 0x59414b4f
private const val ADB_CLSE = 0x45534c43
private const val ADB_WRTE = 0x45545257
private const val ADB_STLS = 0x534c5453
private const val ADB_AUTH_TOKEN = 1
private const val ADB_AUTH_SIGNATURE = 2
private const val ADB_AUTH_RSAPUBLICKEY = 3
