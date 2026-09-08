package com.hikaricalyx.lmnflash.firmware

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

/** Reads the Motorola/Lenovo firmware fields exposed by the running device. */
data class CurrentDeviceInfo(
    val imei: String,
    val serialNumber: String,
    val model: String,
    val carrier: String,
    val fingerprint: String,
    val platform: Platform,
    val fsgVersion: String,
    val simCount: Int,
)

class CurrentDeviceInfoReader(context: Context) {
    private val appContext = context.applicationContext

    fun isSupportedDevice(): Boolean = systemProperty("ro.product.brand")
        .ifBlank { Build.BRAND }
        .trim()
        .let { brand ->
            brand.equals("lenovo", ignoreCase = true) ||
                brand.equals("motorola", ignoreCase = true) ||
                brand.equals("fcnt", ignoreCase = true) ||
                brand.equals("nec", ignoreCase = true)
        }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun read(): CurrentDeviceInfo? {
        if (!isSupportedDevice()) return null

        val hardware = systemProperty("ro.hardware")
        return CurrentDeviceInfo(
            imei = currentDeviceImei(),
            serialNumber = firstSystemProperty(
                "ro.serialno",
                "sys.customsn.showcode",
                "ro.lenovosn2",
                "persist.radio.factory_phone_sn",
                "gsm.lenovosn2",
                "persist.sys.snvalue",
                "ro.odm.lenovo.sn",
            ),
            model = systemProperty("ro.boot.hardware.sku"),
            carrier = systemProperty("ro.carrier"),
            fingerprint = systemProperty("ro.build.fingerprint"),
            platform = if (hardware.equals("qcom", ignoreCase = true)) Platform.QUALCOMM else Platform.MEDIATEK,
            fsgVersion = firstSystemProperty(
                "vendor.ril.baseband.config.version",
                "ril.baseband.config.version",
                "persist.radio.baseband.config.version",
            ),
            simCount = if (systemProperty("ro.vendor.hw.dualsim").equals("true", ignoreCase = true)) 2 else 1,
        )
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun currentDeviceImei(): String = telephonyImei().ifBlank {
        firstSystemProperty("device.imei1", "gsm.imei1")
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun telephonyImei(): String = runCatching {
        val telephony = appContext.getSystemService(TelephonyManager::class.java) ?: return@runCatching ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephony.getImei(0).orEmpty()
        else telephony.getDeviceId(0).orEmpty()
    }.getOrDefault("")

    private fun firstSystemProperty(vararg names: String): String = names
        .asSequence()
        .map(::systemProperty)
        .firstOrNull(String::isNotBlank)
        .orEmpty()

    /**
     * Accesses Android properties through getprop first because reflection on the hidden
     * android.os.SystemProperties API is blocked on many current Android releases.
     */
    private fun systemProperty(name: String): String = propertyFromGetprop(name).ifBlank {
        propertyFromHiddenApi(name)
    }

    private fun propertyFromGetprop(name: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name)
            .redirectErrorStream(true)
            .start()
        try {
            process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
        } finally {
            process.destroy()
        }
    }.getOrDefault("")

    private fun propertyFromHiddenApi(name: String): String = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrDefault("")
}
