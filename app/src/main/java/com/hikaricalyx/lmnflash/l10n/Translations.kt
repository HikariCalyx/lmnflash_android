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

    fun error(message: String): String = when (message) {
        "IMEI must contain digits only" -> text("imei-error-digits")
        "IMEI must be 14 or 15 digits" -> text("imei-error-length")
        "IMEI checksum is invalid" -> text("imei-error-checksum")
        "XT model code is required" -> text("retcn-error-model")
        "Build fingerprint is required" -> text("retcn-error-fingerprint")
        "Carrier is required" -> text("retcn-error-carrier")
        "Serial number is required" -> text("retcn-error-sn")
        "FSG version is required for Qualcomm" -> text("retcn-error-fsg")
        "Model name is required" -> text("by-model-error-required")
        "Log in to look up firmware" -> text("login-prompt")
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
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context, locale.toLanguageTag()) { Translator.load(context, locale) }
}
