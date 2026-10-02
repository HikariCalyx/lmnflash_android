package com.hikaricalyx.lmnflash.firmwareflash

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

sealed interface FlashOperation {
    val label: String

    data class Flash(val partition: String, val filename: String, val md5: String?) : FlashOperation {
        override val label: String = "flash $partition ($filename)"
    }
    data class Erase(val partition: String) : FlashOperation {
        override val label: String = "erase $partition"
    }
    data class Oem(val command: String) : FlashOperation {
        override val label: String = "oem $command"
    }
    data class Getvar(val variable: String) : FlashOperation {
        override val label: String = "getvar $variable"
    }
}

enum class FlashPart { AP, BP, BL }

private val BP_PARTITIONS = setOf("radio", "modem", "fsg", "md1img", "md1img2")
private val BL_PARTITIONS = setOf(
    "partition", "gpt", "bootloader", "motoboot", "preloader", "pit", "diskmap", "pi_img",
    "lk", "scp", "sspm", "mcupm", "gpueb", "spmfw", "gz", "tee", "dpm", "vcp",
    "efuse", "efusebackup", "keystorage", "fwbl1",
)

/** PC-equivalent grouping for selectable image rows; non-image commands deliberately have no part. */
fun FlashOperation.flashPart(mediatek: Boolean): FlashPart? {
    val image = this as? FlashOperation.Flash ?: return null
    val partition = normalizedPartition(image.partition)
    return when {
        partition in BP_PARTITIONS -> FlashPart.BP
        partition in BL_PARTITIONS || (mediatek && partition == "dtbo") -> FlashPart.BL
        else -> FlashPart.AP
    }
}

fun FlashPackage.isMediatek(): Boolean = operations.filterIsInstance<FlashOperation.Flash>().any { image ->
    normalizedPartition(image.partition) == "preloader" || image.filename.contains("preloader", ignoreCase = true)
}


data class FlashPackage(
    val displayName: String,
    val model: String?,
    val softwareVersion: String?,
    val cid: String?,
    val projectCode: String?,
    val ignoredPartitions: List<String>,
    val operations: List<FlashOperation>,
    private val archive: File,
    private val flashfileEntry: String,
    private val imageEntries: Map<String, String>,
) {
    fun imageSize(operation: FlashOperation.Flash): Long = ZipFile(archive).use { zip ->
        zip.getEntry(imageEntries[operation.filename])?.size
            ?: error("Missing image in firmware ZIP: ${operation.filename}")
    }

    fun openImage(operation: FlashOperation.Flash): InputStream {
        val zip = ZipFile(archive)
        val entry = zip.getEntry(imageEntries[operation.filename]) ?: run {
            zip.close()
            error("Missing image in firmware ZIP: ${operation.filename}")
        }
        return object : FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                try { super.close() } finally { zip.close() }
            }
        }
    }

    fun deleteCachedArchive() {
        archive.delete()
    }

    internal fun readVbmetaIdentifier(): String? {
        val vbmeta = operations.filterIsInstance<FlashOperation.Flash>()
            .firstOrNull { normalizedPartition(it.partition) == "vbmeta" }
            ?: return null
        openImage(vbmeta).use { input ->
            val bytes = input.readAtMost(MAX_VBMETA_SCAN)
            val marker = "HAB_META".toByteArray()
            for (index in 0..bytes.size - marker.size) {
                if (bytes.copyOfRange(index, index + marker.size).contentEquals(marker)) {
                    val identifier = bytes.copyOfRange(index + marker.size, minOf(bytes.size, index + marker.size + 64))
                        .dropWhile { it == 0.toByte() }
                        .takeWhile { it.toInt().toChar().isLetterOrDigit() || it == '_'.code.toByte() }
                        .toByteArray().toString(Charsets.US_ASCII)
                    val split = identifier.lastIndexOf('_')
                    if (split > 0 && identifier.substring(split + 1).toUIntOrNull() != null) return identifier
                }
            }
        }
        return null
    }

    internal fun flashfilePath(): String = flashfileEntry

    /** Verifies one selected image immediately before its Fastboot flash command. */
    fun verifyChecksum(operation: FlashOperation.Flash, onLog: (String) -> Unit) {
        val expected = operation.md5?.trim()?.takeIf(String::isNotBlank) ?: return
        onLog("verifying ${operation.filename}")
        val actual = md5(openImage(operation)) { _ -> }
        require(actual.equals(expected, true)) { "MD5 mismatch for ${operation.filename}: expected $expected, got $actual" }
    }

    /** Verifies checksums only for the operations the user has kept enabled. */
    fun verifyChecksums(selectedOperations: List<FlashOperation>, onLog: (String) -> Unit) {
        selectedOperations.filterIsInstance<FlashOperation.Flash>().forEach { operation -> verifyChecksum(operation, onLog) }
    }

    /** Verifies every checksum supplied by the factory flashfile before any device write begins. */
    fun verifyChecksums(onLog: (String) -> Unit) = verifyChecksums(operations, onLog)

    companion object {
        private const val MAX_FLASHFILE_BYTES = 1 shl 20
        private const val MAX_VBMETA_SCAN = 8 shl 20
        private val IGNORED = setOf("cid", "persist", "secdata", "secdatabackup")

        fun load(context: Context, uri: Uri, onProgress: (Long, Long) -> Unit): FlashPackage {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        name to if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    }
                } ?: ("firmware.zip" to 0L)
            require(displayName.first.endsWith(".zip", ignoreCase = true)) { "Select a firmware ZIP package." }

            val destination = File(context.cacheDir, "firmware-packages").apply { mkdirs() }
                .resolve("${UUID.randomUUID()}.zip")
            try {
                if (displayName.second > 0) {
                    require((destination.parentFile?.usableSpace ?: 0L) > displayName.second) { "Not enough free storage to copy this firmware ZIP." }
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied, maxOf(copied, displayName.second))
                        }
                    }
                } ?: error("Unable to read the selected firmware ZIP.")

                ZipFile(destination).use { zip ->
                    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                    entries.forEach { entry -> requireSafeEntry(entry.name) }
                    val flashfile = entries.singleOrNull { entry ->
                        entry.name.substringAfterLast('/').equals("flashfile.xml", ignoreCase = true) && entry.name.count { it == '/' } <= 3
                    } ?: error("The ZIP must contain exactly one flashfile.xml at its root or within three folders.")
                    require(flashfile.size in 1..MAX_FLASHFILE_BYTES.toLong()) { "flashfile.xml is missing or too large." }
                    val xml = decodeXml(zip.getInputStream(flashfile).use(InputStream::readBytes))
                    val parsed = parseFlashfile(xml)
                    val base = flashfile.name.substringBeforeLast('/', "")
                    val indexed = entries.associateBy { it.name.lowercase() }
                    val files = linkedMapOf<String, String>()
                    parsed.operations.filterIsInstance<FlashOperation.Flash>().forEach { operation ->
                        val resolved = safeJoin(base, operation.filename)
                        val match = indexed[resolved.lowercase()]?.name
                            ?: error("Missing image in firmware ZIP: ${operation.filename}")
                        files[operation.filename] = match
                    }
                    val (operations, ignored) = parsed.operations.fold(mutableListOf<FlashOperation>() to mutableListOf<String>()) { (kept, skipped), operation ->
                        val partition = when (operation) {
                            is FlashOperation.Flash -> operation.partition
                            is FlashOperation.Erase -> operation.partition
                            else -> null
                        }
                        if (partition != null && normalizedPartition(partition) in IGNORED) {
                            if (partition !in skipped) skipped += partition
                        } else kept += operation
                        kept to skipped
                    }
                    require(operations.isNotEmpty()) { "The flashfile contains no safe flashing steps." }
                    val partial = FlashPackage(displayName.first, parsed.model, parsed.softwareVersion, parsed.cid, null, ignored, operations, destination, flashfile.name, files)
                    val identifier = partial.readVbmetaIdentifier()
                    val project = identifier?.substringBeforeLast('_')?.takeIf(String::isNotBlank)
                    val cid = parsed.cid ?: identifier?.substringAfterLast('_')?.toUIntOrNull()?.let(::formatCid)
                    return partial.copy(cid = cid, projectCode = project)
                }
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }

        fun md5(input: InputStream, onProgress: (Long) -> Unit): String {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1 shl 20)
            var readTotal = 0L
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    readTotal += count
                    onProgress(readTotal)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun cidMismatch(packageCid: String?, deviceCid: String?): Boolean = packageCid != null && deviceCid != null &&
            !packageCid.equals(deviceCid, true) && !deviceCid.equals("0x0000", true) && !deviceCid.equals("0x00FF", true)
        fun projectMismatch(project: String?, product: String?): Boolean = !project.isNullOrBlank() && !product.isNullOrBlank() && !project.equals(product, true)

        private data class Parsed(val model: String?, val softwareVersion: String?, val cid: String?, val operations: List<FlashOperation>)

        private fun parseFlashfile(xml: String): Parsed {
            val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
            var rootSeen = false
            var inHeader = false
            var inSteps = false
            var model: String? = null
            var version: String? = null
            var cid: String? = null
            val operations = mutableListOf<FlashOperation>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "flashing" -> {
                            require(!rootSeen && parser.depth == 1) { "Expected a single <flashing> root element." }
                            rootSeen = true
                        }
                        "header" -> inHeader = true
                        "steps" -> inSteps = true
                        "phone_model" -> if (inHeader) model = parser.getAttributeValue(null, "model")?.trim()?.takeIf(String::isNotBlank)
                        "software_version" -> if (inHeader) version = parser.getAttributeValue(null, "version")?.trim()?.takeIf(String::isNotBlank)
                        "cid_value" -> if (inHeader) cid = parser.getAttributeValue(null, "value")?.let(::normalizeCid)
                        "step" -> if (inSteps) operations += parseStep(parser)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "header" -> inHeader = false
                        "steps" -> inSteps = false
                    }
                }
                parser.next()
            }
            require(rootSeen) { "Expected a <flashing> root element." }
            require(operations.isNotEmpty()) { "The flashfile contains no <step> elements." }
            return Parsed(model, version, cid, operations)
        }

        private fun parseStep(parser: XmlPullParser): FlashOperation {
            fun required(name: String): String = parser.getAttributeValue(null, name)?.trim()?.takeIf(String::isNotBlank)
                ?: error("A ${parser.getAttributeValue(null, "operation") ?: ""} step has no `$name` attribute.")
            return when (val operation = required("operation")) {
                "flash" -> FlashOperation.Flash(required("partition"), required("filename"), parser.getAttributeValue(null, "MD5") ?: parser.getAttributeValue(null, "md5"))
                "erase" -> FlashOperation.Erase(required("partition"))
                "oem" -> FlashOperation.Oem(required("var"))
                "getvar" -> FlashOperation.Getvar(required("var"))
                else -> error("Unsupported flashfile step operation: $operation")
            }
        }

        private fun decodeXml(bytes: ByteArray): String = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> decodeUtf16(bytes.copyOfRange(2, bytes.size), true)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> decodeUtf16(bytes.copyOfRange(2, bytes.size), false)
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
        private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String {
            require(bytes.size % 2 == 0) { "flashfile.xml is not valid UTF-16." }
            val chars = CharArray(bytes.size / 2) { index ->
                val first = bytes[index * 2].toInt() and 0xff
                val second = bytes[index * 2 + 1].toInt() and 0xff
                (if (littleEndian) first or (second shl 8) else second or (first shl 8)).toChar()
            }
            return chars.concatToString()
        }
        private fun requireSafeEntry(name: String) {
            require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && name.split('/').none { it.isBlank() || it == "." || it == ".." }) { "The ZIP contains an unsafe entry path." }
        }
        private fun safeJoin(base: String, child: String): String {
            require(!child.startsWith('/') && !child.contains('\\')) { "Unsafe image path: $child" }
            val segments = (if (base.isBlank()) emptyList() else base.split('/')) + child.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe image path: $child" }
            return segments.joinToString("/")
        }
        private fun normalizedPartition(value: String): String = value.trim().lowercase().replace(Regex("_(a|b)$"), "")
        private fun normalizeCid(value: String): String {
            val clean = value.trim()
            val number = clean.removePrefix("0x").removePrefix("0X").toUIntOrNull(16) ?: clean.toUIntOrNull()
            return number?.let(::formatCid) ?: clean
        }
        private fun formatCid(value: UInt): String = "0x${value.toString(16).uppercase().padStart(4, '0')}"
    }
}

private fun normalizedPartition(value: String): String = value.trim().lowercase().replace(Regex("_(a|b)$"), "")

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(limit)
    val buffer = ByteArray(minOf(64 * 1024, limit))
    var remaining = limit
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
