package com.hikaricalyx.lmnflash.firmware

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.net.ssl.HttpsURLConnection

enum class LookupMode(val title: String) {
    ROW_SMARTPHONE("ROW Smartphones"),
    RETCN_SMARTPHONE("RETCN Smartphones"),
    TABLET("Tablets"),
    BY_MODEL("Lookup by Model"),
}

enum class Platform(val title: String) { QUALCOMM("Qualcomm"), MEDIATEK("MediaTek") }
enum class DeviceCategory(val title: String, val code: String) {
    PHONE("Phone", "phone"), TABLET("Tablet", "tablet"), SMART("Smart", "smart")
}

data class Session(val token: String, val clientUuid: String, val fullName: String? = null)
data class LoginUrl(val url: String, val state: String)

data class RetcnRequest(
    val imei: String,
    val serialNumber: String,
    val fingerprint: String,
    val model: String,
    val carrier: String,
    val platform: Platform,
    val fsgVersion: String,
    val simCount: Int,
)

data class FirmwareInfo(
    val marketName: String = "",
    val modelName: String = "",
    val saleModel: String = "",
    val carrier: String = "",
    val comments: String = "",
    val publishDate: String = "",
    val romMatchId: String = "",
    val fingerprint: String = "",
    val romId: String = "",
    val downloadUri: String = "",
    val toolUri: String = "",
    val fileName: String = "",
    val fileSize: String = "",
    val rawJson: String = "",
)

data class CnTabletInfo(
    val productName: String = "",
    val productModel: String = "",
    val marketName: String = "",
    val compatibleMtm: String = "",
    val latestVersion: String = "",
    val resourceId: String = "",
    val downloadUri: String = "",
    val publishDate: String = "",
    val fileName: String = "",
    val fileSize: String = "",
    val unzipPassword: String = "FC(fv:SknR",
)

sealed interface LookupResult {
    data class Standard(val info: FirmwareInfo) : LookupResult
    data class CnTablet(val info: CnTabletInfo) : LookupResult
}

class FirmwareException(message: String, val authExpired: Boolean = false) : Exception(message)

/** Persists the bearer token encrypted with an Android Keystore AES-GCM key. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("firmware_session", Context.MODE_PRIVATE)

    fun load(): Session? {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0)
        if (System.currentTimeMillis() - savedAt >= SESSION_VALIDITY_MS) {
            clearToken()
            return null
        }
        val token = runCatching { decrypt(preferences.getString(KEY_TOKEN, "").orEmpty()) }.getOrElse {
            clearToken()
            return null
        }.trim()
        val uuid = preferences.getString(KEY_UUID, "")?.trim().orEmpty()
        return token.takeIf { it.isNotEmpty() }?.let { value -> uuid.takeIf { it.isNotEmpty() }?.let { Session(value, it) } }
    }

    fun clientUuid(): String = preferences.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_UUID, it).apply()
    }

    fun save(session: Session) {
        preferences.edit()
            .putString(KEY_TOKEN, encrypt(session.token))
            .putString(KEY_UUID, session.clientUuid)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearToken() {
        preferences.edit().remove(KEY_TOKEN).remove(KEY_SAVED_AT).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val (encodedIv, encodedCiphertext) = value.split(":", limit = 2).takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("Malformed encrypted session")
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun secretKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_UUID = "client_uuid"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_ALIAS = "lmnflash_session_key"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val SESSION_VALIDITY_MS = 3 * 60 * 60 * 1000L
    }
}

class FirmwareRepository {
    suspend fun fetchLoginUrl(clientUuid: String): LoginUrl = execute {
        val locale = lenovoLocale()
        val language = locale.replace('_', '-')
        val body = JSONObject()
            .put("client", JSONObject().put("version", CLIENT_VERSION))
            .put("dparams", JSONObject().put("key", "TIP_URL"))
            .put("language", language)
            .put("windowsInfo", "Windows 10, x64-based PC")
        val response = postJson(
            LOGIN_ENDPOINT,
            body,
            mapOf(
                "Cache-Control" to "no-cache",
                "Request-Tag" to "lmsa",
                "clientVersion" to CLIENT_VERSION,
                "language" to language,
                "windowsInfo" to base64("Windows 10"),
                "clientUUID" to clientUuid,
            ),
            timeoutMs = LOGIN_TIMEOUT_MS,
        )
        if (response.json.optString("code") != "0000") throw FirmwareException(
            "Login URL request failed: ${response.json.optString("desc", "unknown error")}",
        )
        val rawUrl = response.json.optString("content")
        if (rawUrl.isBlank()) throw FirmwareException("Login URL response has no login URL")
        val uri = Uri.parse(rawUrl).buildUpon()
            .appendQueryParameter("lenovoid.lang", locale)
            .appendQueryParameter("prompt", "login")
            .build()
        val state = uri.getQueryParameter("state")?.takeIf(String::isNotBlank)
            ?: throw FirmwareException("Generated login URL has no valid state")
        LoginUrl(uri.toString(), state)
    }

    fun parseLoginCallback(input: String, expectedState: String): Session {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw FirmwareException("Callback URL is empty")
        val callback = if (trimmed.startsWith("{")) {
            val json = try { JSONObject(trimmed) } catch (error: Exception) {
                throw FirmwareException("Invalid JSON callback: ${error.message}")
            }
            listOf("content", "msg", "desc")
                .map { json.optString(it) }
                .firstOrNull { it.startsWith("softwarefix://", ignoreCase = true) }
                ?: throw FirmwareException("JSON contains no SoftwareFix callback URL")
        } else trimmed
        val uri = Uri.parse(callback)
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            throw FirmwareException("Browser success-page URLs cannot be reused; paste the SoftwareFix://callback URL")
        }
        if (!uri.scheme.equals("softwarefix", true) || !uri.host.equals("callback", true)) {
            throw FirmwareException("Invalid SoftwareFix callback")
        }
        // Lenovo's SoftwareFix callback commonly omits OAuth state. When the
        // server supplies one, it must still belong to this login attempt.
        val callbackStates = uri.getQueryParameters("state")
        if (callbackStates.isNotEmpty() && callbackStates.singleOrNull() != expectedState) {
            throw FirmwareException("Login callback does not match the active login request")
        }
        uri.getQueryParameters("error").firstOrNull { it.isNotBlank() }?.let { throw FirmwareException("Login failed: $it") }
        val tokens = uri.getQueryParameters("Authorization")
        if (tokens.size != 1 || tokens.single().isBlank()) throw FirmwareException("SoftwareFix callback has no single Authorization value")
        val token = tokens.single().trim().removePrefixIgnoreCase("Bearer ")
        return Session(token, "", uri.getQueryParameters("fullName").firstOrNull { it.isNotBlank() })
    }

    suspend fun lookupByImei(imei: String, session: Session): FirmwareInfo = execute {
        authenticatedLookup(
            ROW_ENDPOINT, session, "en-US",
            JSONObject()
                .put("client", JSONObject().put("version", CLIENT_VERSION))
                .put("dparams", JSONObject().put("imei", imei))
                .put("language", "en-US")
                .put("windowsInfo", WINDOWS_INFO),
        )
    }

    suspend fun lookupRetcn(request: RetcnRequest, session: Session): FirmwareInfo = execute {
        val params = JSONObject()
            .put("fingerPrint", request.fingerprint)
            .put("roCarrier", request.carrier)
            .put("category", "phone")
        if (request.platform == Platform.QUALCOMM) params.put("fsgVersion.qcom", request.fsgVersion)
        else params.put("simCount", if (request.simCount == 2) "Dual" else "Single")
        authenticatedLookup(
            RESOURCE_ENDPOINT, session, "zh-CN",
            JSONObject()
                .put("client", JSONObject().put("version", CLIENT_VERSION))
                .put("dparams", JSONObject()
                    .put("modelName", request.model).put("code", "0000").put("params", params)
                    .put("imei", request.imei).put("imei2", "").put("sn", request.serialNumber)
                    .put("channelId", "0x00").put("matchType", 0))
                .put("language", "zh-CN").put("windowsInfo", WINDOWS_INFO),
        )
    }

    suspend fun lookupModelParams(model: String, session: Session): List<String> = execute {
        val response = authenticatedApi(
            MODEL_PARAMS_ENDPOINT, session, "en-US",
            JSONObject().put("client", JSONObject().put("version", CLIENT_VERSION))
                .put("dparams", JSONObject().put("modelName", model))
                .put("language", "en-US").put("windowsInfo", WINDOWS_INFO),
        )
        val params = response.json.optJSONObject("content")?.optJSONArray("params") ?: JSONArray()
        buildList { for (index in 0 until params.length()) params.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
    }

    suspend fun lookupByModel(
        model: String, category: DeviceCategory, countryCode: String, parameters: Map<String, String>, session: Session,
    ): FirmwareInfo = execute {
        val params = JSONObject().apply {
            parameters.forEach { (key, value) -> put(key, value) }
            put("category", category.code)
            if (category != DeviceCategory.PHONE && countryCode.isNotBlank()) put("countryCode", countryCode)
        }
        authenticatedLookup(
            RESOURCE_ENDPOINT, session, "en-US",
            JSONObject().put("client", JSONObject().put("version", CLIENT_VERSION))
                .put("dparams", JSONObject().put("modelName", model).put("code", "0000").put("params", params)
                    .put("imei", "").put("imei2", "").put("sn", "").put("matchType", 1))
                .put("language", "en-US").put("windowsInfo", WINDOWS_INFO),
        )
    }

    suspend fun lookupTablet(serialNumber: String, session: Session): LookupResult = execute {
        val cnResult = runCatching { lookupCnTablet(serialNumber) }
        cnResult.getOrNull()?.let { return@execute LookupResult.CnTablet(it) }

        try {
            LookupResult.Standard(lookupTabletRow(serialNumber, session))
        } catch (rowError: FirmwareException) {
            if (rowError.authExpired) throw rowError
            val cnError = cnResult.exceptionOrNull()?.message
            throw FirmwareException(listOfNotNull(
                cnError?.let { "CN lookup failed: $it" },
                "ROW lookup failed: ${rowError.message}",
            ).joinToString("; "))
        }
    }

    private fun lookupTabletRow(serialNumber: String, session: Session): FirmwareInfo = authenticatedLookup(
        TABLET_ROW_ENDPOINT, session, "en-US",
        JSONObject().put("client", JSONObject().put("version", CLIENT_VERSION))
            .put("dparams", JSONObject().put("sn", serialNumber))
            .put("language", "en-US").put("windowsInfo", WINDOWS_INFO),
    )

    private fun lookupCnTablet(serialNumber: String): CnTabletInfo? {
        val machine = postJson(
            "$CN_MACHINE_ENDPOINT?MachineNo=${Uri.encode(serialNumber)}", null,
            mapOf("Content-Type" to "application/json;charset=UTF-8"), REQUEST_TIMEOUT_MS,
        ).json
        if (machine.optInt("StatusCode") != 200) return null
        val mtm = machine.optJSONObject("data")?.optString("MTM")?.trim().orEmpty()
        if (mtm.isEmpty()) throw FirmwareException("CN machine lookup returned no MTM")
        val response = postJson(
            CN_FIRMWARE_ENDPOINT, JSONObject().put("mtm", mtm),
            mapOf("Content-Type" to "application/json;charset=UTF-8"), REQUEST_TIMEOUT_MS,
        ).json
        val item = response.optJSONArray("data")?.optJSONObject(0) ?: return null
        if (response.optInt("code") != 200) return null
        val downloadUri = item.string("download_url")
        val headers = downloadMetadata(downloadUri)
        return CnTabletInfo(
            productName = item.string("product_name"), productModel = item.string("product_model"),
            marketName = item.string("market_name"),
            compatibleMtm = item.string("mtm").split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", "),
            latestVersion = item.string("latest_version"), resourceId = item.string("id"), downloadUri = downloadUri,
            publishDate = headers?.lastModified.orEmpty(), fileName = fileName(downloadUri), fileSize = headers?.length?.let(::humanSize).orEmpty(),
        )
    }

    private fun authenticatedLookup(endpoint: String, session: Session, language: String, body: JSONObject): FirmwareInfo {
        val response = authenticatedApi(endpoint, session, language, body)
        val item = response.json.optJSONArray("content")?.optJSONObject(0)
            ?: throw FirmwareException("API returned no matching resource")
        val rom = item.optJSONObject("romResource") ?: JSONObject()
        val tool = item.optJSONObject("toolResource") ?: JSONObject()
        val uri = rom.string("uri")
        val metadata = downloadMetadata(uri)
        return FirmwareInfo(
            marketName = item.string("marketName"), modelName = item.string("modelName"), saleModel = item.string("saleModel"),
            carrier = item.string("carrier"), comments = item.string("comments"),
            publishDate = item.string("publishDate").ifBlank { metadata?.lastModified.orEmpty() },
            romMatchId = item.string("romMatchId"), fingerprint = item.string("fingerPrint"), romId = rom.string("id"),
            downloadUri = uri, toolUri = tool.string("uri"), fileName = fileName(uri),
            fileSize = metadata?.length?.let(::humanSize).orEmpty(), rawJson = response.raw,
        )
    }

    private fun authenticatedApi(endpoint: String, session: Session, language: String, body: JSONObject): Response {
        val authorization = "Bearer ${session.token.removePrefixIgnoreCase("Bearer ").trim()}"
        val fingerprint = buildFingerprint(authorization, session.clientUuid, endpoint)
        val response = postJson(endpoint, body, mapOf(
            "User-Agent" to USER_AGENT, "Connection" to "Close", "Content-Type" to "application/json", "Request-Tag" to "lmsa",
            "Authorization" to authorization, "X-Device-Fingerprint" to fingerprint, "clientUUID" to session.clientUuid,
            "clientVersion" to CLIENT_VERSION, "windowsInfo" to base64("Microsoft Windows 11"), "language" to language,
            "Cache-Control" to "no-store,no-cache", "Pragma" to "no-cache",
        ), REQUEST_TIMEOUT_MS)
        val code = response.json.optString("code")
        if (code != "0000") throw FirmwareException(
            "API error ${code.ifBlank { "unknown" }}: ${response.json.optString("desc", "request failed")}",
            code.toIntOrNull()?.let { it in 402..409 } == true,
        )
        return response
    }

    private suspend inline fun <T> execute(crossinline block: () -> T): T = block()

    private fun buildFingerprint(authorization: String, clientUuid: String, endpoint: String): String {
        val keyResponse = postJson(RSA_KEY_ENDPOINT, null, mapOf(
            "Cache-Control" to "no-cache", "Request-Tag" to "lmsa", "Authorization" to authorization, "clientUUID" to clientUuid,
        ), REQUEST_TIMEOUT_MS).json
        val encodedKey = keyResponse.optString("desc").trim()
        if (encodedKey.isBlank()) throw FirmwareException("RSA response has no public key")
        val der = Base64.decode(encodedKey.replace(Regex("-----BEGIN [^-]+-----|-----END [^-]+-----|\\s"), ""), Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val interfaceName = endpoint.substringAfterLast('/').substringBeforeLast('.') + "interface"
        val plaintext = "${System.currentTimeMillis()}|$authorization|$interfaceName"
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun postJson(url: String, body: JSONObject?, headers: Map<String, String>, timeoutMs: Int): Response {
        val payload = body?.toString()?.toByteArray(StandardCharsets.UTF_8)
        val connection = open(url, "POST", headers, timeoutMs)
        return connection.useConnection {
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(payload.size)
                outputStream.use { it.write(payload) }
            }
            parseResponse()
        }
    }

    private fun downloadMetadata(url: String): DownloadMetadata? {
        if (url.isBlank()) return null
        return runCatching {
            val head = open(url, "HEAD", emptyMap(), REQUEST_TIMEOUT_MS).useConnection { parseHeaders() }
            head
        }.recoverCatching {
            open(url, "GET", mapOf("Range" to "bytes=0-0"), REQUEST_TIMEOUT_MS).useConnection { parseHeaders(rangeResponse = true) }
        }.getOrNull()
    }

    private fun open(url: String, method: String, headers: Map<String, String>, timeoutMs: Int): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun HttpsURLConnection.parseResponse(): Response {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val raw = stream?.readText().orEmpty()
        if (code !in 200..299) throw FirmwareException("HTTP $code: ${raw.take(300).ifBlank { responseMessage }}")
        return try { Response(raw, JSONObject(raw)) } catch (error: Exception) { throw FirmwareException("Invalid JSON response: ${error.message}") }
    }

    private fun HttpsURLConnection.parseHeaders(rangeResponse: Boolean = false): DownloadMetadata {
        if (responseCode !in 200..299) throw FirmwareException("Download metadata request failed: HTTP $responseCode")
        val length = if (rangeResponse) {
            getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: contentLengthLong.takeIf { it >= 0 }
        } else contentLengthLong.takeIf { it >= 0 }
        return DownloadMetadata(getHeaderField("Last-Modified").orEmpty(), length)
    }

    private inline fun <T> HttpsURLConnection.useConnection(block: HttpsURLConnection.() -> T): T = try { block() } finally { disconnect() }
    private fun InputStream.readText(): String = BufferedReader(reader(StandardCharsets.UTF_8)).use(BufferedReader::readText)
    private fun JSONObject.string(key: String): String = optString(key).trim()
    private fun base64(value: String): String = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private data class Response(val raw: String, val json: JSONObject)
    private data class DownloadMetadata(val lastModified: String, val length: Long?)

    private companion object {
        const val CLIENT_VERSION = "7.6.2.10"
        const val WINDOWS_INFO = "Microsoft Windows 11, x64-based PC"
        const val CN_UNZIP_PASSWORD = "FC(fv:SknR"
        const val REQUEST_TIMEOUT_MS = 120_000
        const val LOGIN_TIMEOUT_MS = 30_000
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.79 Safari/537.36"
        const val LOGIN_ENDPOINT = "https://lsa.lenovo.com/Interface/dictionary/getApiInfo.jhtml"
        const val RSA_KEY_ENDPOINT = "https://lsa.lenovo.com/Interface/common/rsa.jhtml"
        const val ROW_ENDPOINT = "https://lsa.lenovo.com/Interface/rescueDevice/getNewResourceByImei.jhtml"
        const val RESOURCE_ENDPOINT = "https://lsa.lenovo.com/Interface/rescueDevice/getNewResource.jhtml"
        const val TABLET_ROW_ENDPOINT = "https://lsa.lenovo.com/Interface/rescueDevice/getNewResourceBySN.jhtml"
        const val MODEL_PARAMS_ENDPOINT = "https://lsa.lenovo.com/Interface/rescueDevice/getRomMatchParams.jhtml"
        const val CN_MACHINE_ENDPOINT = "https://ptstpd.lenovo.com.cn/home/ConfigurationQuery/getMachineSequenceInfo"
        const val CN_FIRMWARE_ENDPOINT = "https://ptstpd.lenovo.com.cn/home/ConfigurationQuery/getPadFlashingMachine"
    }
}

private fun String.removePrefixIgnoreCase(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
private fun fileName(url: String): String = Uri.decode(url.substringBefore('?').substringAfterLast('/', ""))
private fun humanSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "$bytes B" else "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.') + " ${units[unit]}"
}
private fun lenovoLocale(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
    "zh" -> if (Locale.getDefault().country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")) "zh_TW" else "zh_CN"
    "ja" -> "ja_JP"; "ko" -> "ko_KR"; "ru" -> "ru_RU"; "da" -> "da_DK"; "de" -> "de_DE"; "fr" -> "fr_FR"
    "it" -> "it_IT"; "no", "nb" -> "nb_NO"; "nl" -> "nl_NL"; "pt" -> "pt_BR"; "fi" -> "fi_FI"
    "es" -> "es_ES"; "sv" -> "sv_SE"; "uk" -> "uk_UA"; else -> "en_US"
}

fun validateImei(input: String): Result<String> {
    val digits = input.filterNot(Char::isWhitespace)
    if (digits.isEmpty() || !digits.all(Char::isDigit)) return Result.failure(FirmwareException("IMEI must contain digits only"))
    return when (digits.length) {
        14 -> Result.success(digits + luhnCheckDigit(digits))
        15 -> if (luhnSum(digits, 0) % 10 == 0) Result.success(digits) else Result.failure(FirmwareException("IMEI checksum is invalid"))
        else -> Result.failure(FirmwareException("IMEI must be 14 or 15 digits"))
    }
}
fun validateDigits(input: String): Result<String> {
    val digits = input.filterNot(Char::isWhitespace)
    return if (digits.isNotEmpty() && digits.all(Char::isDigit)) Result.success(digits) else Result.failure(FirmwareException("IMEI must contain digits only"))
}
private fun luhnCheckDigit(digits: String): Int = (10 - luhnSum(digits, 1) % 10) % 10
private fun luhnSum(digits: String, offset: Int): Int = digits.reversed().mapIndexed { index, char ->
    val value = char.digitToInt()
    if ((index + offset) % 2 == 1) (value * 2).let { if (it > 9) it - 9 else it } else value
}.sum()
