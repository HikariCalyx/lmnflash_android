package com.hikaricalyx.lmnflash.firmware

import android.os.Build
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/** Sends best-effort events when a smartphone lookup returns a downloadable firmware image. */
internal class FirmwareTelemetryReporter(
    private val appVersion: String,
) {
    fun reportRowFirmwareFound(imei: String, info: FirmwareInfo) = report(
        lookupType = "ROWSmartphone",
        body = JSONObject()
            .put("imei", imei.toLong())
            .put("xtCode", info.modelName)
            .put("carrier", info.carrier)
            .put("marketName", info.marketName)
            .put("packageName", info.fileName),
    )

    fun reportRetcnFirmwareFound(imei: String, form: RetcnForm, info: FirmwareInfo) = report(
        lookupType = "RETCNSmartphone",
        body = JSONObject()
            .put("imei", imei.toLong())
            .put("psn", form.serialNumber)
            .put("xtCode", form.model)
            .put("carrier", form.carrier)
            .put("fingerprint", form.fingerprint)
            .put("soc", form.platform.telemetryValue())
            .put("fsgver", form.fsgVersion)
            .put("simslot", form.simCount)
            .put("marketName", info.marketName)
            .put("packageName", info.fileName),
    )

    fun reportTabletFirmwareFound(
        psn: String,
        modelCode: String,
        marketName: String,
        packageName: String,
    ) = report(
        lookupType = "Tablet",
        body = JSONObject()
            .put("psn", psn)
            .put("modelCode", modelCode)
            .put("marketName", marketName)
            .put("packageName", packageName),
    )

    private fun report(lookupType: String, body: JSONObject) {
        val payload = JSONObject()
            .put("lookupType", lookupType)
            .put("body", body)
            .put("lmnflash_version", appVersion)
            .put("fingerprint", Build.FINGERPRINT.orEmpty())
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val connection = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            responseStream?.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun Platform.telemetryValue(): String = when (this) {
        Platform.QUALCOMM -> "qcom"
        Platform.MEDIATEK -> "mtk"
    }

    private companion object {
        const val ENDPOINT = "https://api.hikaricalyx.com/LMN/v1/Telemetry"
        const val TIMEOUT_MS = 10_000
    }
}
