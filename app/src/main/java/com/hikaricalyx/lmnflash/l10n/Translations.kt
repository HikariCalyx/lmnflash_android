package com.hikaricalyx.lmnflash.l10n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

enum class AppLanguage(val tag: String, val assetFolder: String, val nativeName: String) {
    ENGLISH("en-US", "en-US", "English"),
    CHINESE_SIMPLIFIED("zh-Hans", "zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "zh-Hant", "繁體中文"),
    JAPANESE("ja", "ja", "日本語"),
    KOREAN("ko", "ko", "한국어"),
    RUSSIAN("ru", "ru", "Русский"),
    DANISH("da", "da", "Dansk"),
    GERMAN("de", "de", "Deutsch"),
    FRENCH("fr", "fr", "Français"),
    ITALIAN("it", "it", "Italiano"),
    NORWEGIAN("nb", "nb", "Norsk"),
    DUTCH("nl", "nl", "Nederlands"),
    PORTUGUESE_BRAZIL("pt-BR", "pt-BR", "Português (Brasil)"),
    FINNISH("fi", "fi", "Suomi"),
    SPANISH("es", "es", "Español"),
    SWEDISH("sv", "sv", "Svenska"),
    UKRAINIAN("uk", "uk", "Українська");

    companion object {
        fun forLocale(locale: Locale): AppLanguage = when (locale.language) {
            "zh" -> if (locale.script.equals("Hant", true) || locale.country in setOf("TW", "HK", "MO")) CHINESE_TRADITIONAL else CHINESE_SIMPLIFIED
            "pt" -> PORTUGUESE_BRAZIL
            "no" -> NORWEGIAN
            else -> entries.firstOrNull { it.tag.substringBefore('-') == locale.language } ?: ENGLISH
        }
    }
}

/** Loads the reference FTL catalog packaged in this app's assets. */
class Translator private constructor(private val messages: Map<String, String>) {
    fun text(key: String, vararg args: Pair<String, Any?>): String {
        var value = messages[key] ?: ENGLISH[key] ?: key
        args.forEach { (name, argument) ->
            value = value.replace("{ \$$name }", argument?.toString().orEmpty())
        }
        return value
    }

    fun error(message: String): String = when {
        message == "IMEI must contain digits only" -> text("imei-error-digits")
        message == "IMEI must be 14 or 15 digits" -> text("imei-error-length")
        message == "IMEI checksum is invalid" -> text("imei-error-checksum")
        message == "XT model code is required" -> text("retcn-error-model")
        message == "Build fingerprint is required" -> text("retcn-error-fingerprint")
        message == "Carrier is required" -> text("retcn-error-carrier")
        message == "Serial number is required" -> text("retcn-error-sn")
        message == "FSG version is required for Qualcomm" -> text("retcn-error-fsg")
        message == "Model name is required" -> text("by-model-error-required")
        message == "Log in to look up firmware" -> text("login-prompt")
        message == "Enter the unlock key first." -> text("flash-bootloader-key-required")
        message == "Turn on \"OEM unlocking\" in Developer Options, then try again." -> text("flash-bootloader-oem-unlocking-required")
        message == "Bootloader unlock request has been cancelled." -> text("flash-bootloader-unlock-cancelled")
        message == "Bootloader unlock failed due to wrong unlock key." -> text("flash-bootloader-unlock-wrong-key")
        message == "No fastboot device connected" -> text("retcn-fill-fastboot-no-device")
        message == "No ADB device connected" -> text("adb-error-no-device")
        message.startsWith("ADB authorization was not granted") -> text("adb-error-authorization")
        message == "ADB shell command timed out" -> text("adb-error-command-timeout")
        message == "ADB authentication protocol failed" || message == "Unexpected ADB response during authentication" -> text("adb-error-authentication")
        message == "No supported Motorola device found" -> text("retcn-fill-fastboot-not-motorola")
        message == "USB permission was denied." -> text("fastboot-error-permission-denied")
        message == "USB permission request timed out." -> text("fastboot-error-permission-timeout")
        message in setOf(
            "ADB USB endpoints were not found",
            "Unable to open the ADB USB device",
            "Unable to claim the ADB USB interface",
            "ADB USB write timed out",
            "ADB USB read timed out",
            "Invalid ADB packet header",
            "Invalid ADB packet size",
            "Invalid ADB packet checksum",
        ) -> text("adb-error-transport")
        message in setOf(
            "Fastboot USB endpoints were not found",
            "Unable to open the Fastboot USB device",
            "Unable to claim the Fastboot USB interface",
            "Fastboot command returned too many response packets",
            "Fastboot USB write timed out",
            "Fastboot USB read timed out",
            "Short Fastboot response",
        ) -> text("fastboot-error-transport")
        message == "Fastboot command timed out waiting for a response" -> text("fastboot-error-command-timeout")
        message.startsWith("Unexpected Fastboot response: ") -> text("fastboot-error-unexpected-response", "header" to message.substringAfter(": "))
        message == "Failed to get unlock data: the device did not return its Device ID." -> text("fastboot-error-unlock-data-unavailable")
        message == "No unlock data returned by the device." -> text("fastboot-error-unlock-data-empty")
        message == "Fastboot operation failed" || message == "Unable to read device" -> text("fastboot-error-operation")
        else -> message
    }

    companion object {
        private val ENGLISH = mapOf("app-title" to "LMN Flash")

        fun load(context: Context, locale: Locale): Translator {
            val language = AppLanguage.forLocale(locale)
            val english = readCatalog(context, "en-US")
            val selected = if (language == AppLanguage.ENGLISH) emptyMap() else readCatalog(context, language.assetFolder)
            return Translator(english + selected)
        }

        private fun readCatalog(context: Context, folder: String): Map<String, String> = runCatching {
            context.assets.open("$folder/app.ftl").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val separator = line.indexOf(" = ")
                    if (separator > 0 && !line.startsWith('#')) line.substring(0, separator) to line.substring(separator + 3) else null
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }
}

@Composable
fun rememberTranslator(): Translator {
    val locale = LocalConfiguration.current.locales[0]
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context, locale.toLanguageTag()) { Translator.load(context, locale) }
}
