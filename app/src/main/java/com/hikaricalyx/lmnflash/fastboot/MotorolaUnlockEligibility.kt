package com.hikaricalyx.lmnflash.fastboot

import android.net.Uri
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

private const val VERIFY_PHONE_BASE = "https://en-us.support.motorola.com/cc/productRegistration/verifyPhone"
private const val ELIGIBILITY_TIMEOUT_MS = 30_000

/** Best-effort Motorola bootloader-unlock eligibility lookup, matching the desktop flow. */
fun checkUnlockEligibility(deviceId: String): Boolean {
    val parts = deviceId.split('#')
    require(parts.size >= 4) { "Device ID has fewer than the 4 expected # separated parts" }
    val url = "$VERIFY_PHONE_BASE/${Uri.encode(parts[0])}/${Uri.encode(parts[3])}/${Uri.encode(parts[2])}/"
    val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = ELIGIBILITY_TIMEOUT_MS
        readTimeout = ELIGIBILITY_TIMEOUT_MS
        instanceFollowRedirects = true
    }
    val body = try {
        if (connection.responseCode !in 200..299) error("Unlock eligibility request failed: HTTP ${connection.responseCode}")
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }.lowercase()
    return when {
        "not qualified" in body -> false
        "qualif" in body -> true
        else -> error("Unexpected unlock eligibility response")
    }
}
