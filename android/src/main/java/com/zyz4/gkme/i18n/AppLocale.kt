package com.zyz4.gkme.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

data class LanguageOption(val code: String, val label: String)

/**
 * Per-app locale selection backed by `values-xx` resources.
 *
 * The chosen language is stored in SharedPreferences and applied by wrapping the base
 * Context of the Application / Activities / Services, so ordinary `getString(R.string.…)`
 * calls resolve to the matching `values-xx` directory. The default preference follows
 * the system locale and falls back to [SOURCE_LANGUAGE] when unsupported.
 */
object AppLocale {

    const val SOURCE_LANGUAGE = "zh"
    const val SYSTEM_LANGUAGE = "system"
    private const val PREFS_NAME = "i18n_prefs"
    private const val KEY_LANGUAGE = "language"

    /** The persisted preference: either [SYSTEM_LANGUAGE] or an exact supported code. */
    fun storedLanguage(context: Context): String {
        val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM_LANGUAGE) ?: SYSTEM_LANGUAGE
        if (code == SYSTEM_LANGUAGE) return code
        return if (languageCodes(context).any { it == code }) code else SYSTEM_LANGUAGE
    }

    /** The concrete language used for resource lookup; never [SYSTEM_LANGUAGE]. */
    fun currentLanguage(context: Context): String {
        val code = storedLanguage(context)
        return if (code == SYSTEM_LANGUAGE) resolveSystemLanguage(context) else code
    }

    fun availableLanguages(context: Context): List<LanguageOption> {
        val res = context.resources
        val labels = res.getStringArray(com.zyz4.gkme.R.array.app_language_labels)
        val result = mutableListOf(
            LanguageOption(SYSTEM_LANGUAGE, res.getString(com.zyz4.gkme.R.string.i18n_system_default))
        )
        languageCodes(context).forEachIndexed { index, code ->
            result += LanguageOption(code, labels.getOrElse(index) { code })
        }
        return result
    }

    /**
     * Persists [code] and refreshes the application-level context used by [AppStrings].
     * Returns true when the language actually changed; callers should recreate the activity.
     */
    fun setLanguage(context: Context, code: String): Boolean {
        if (code == storedLanguage(context)) return false
        if (code != SYSTEM_LANGUAGE && languageCodes(context).none { it == code }) return false
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code)
            .apply()
        AppStrings.init(wrap(app))
        return true
    }

    /** Wraps [base] with the selected locale so resource lookups use `values-xx`. */
    fun wrap(base: Context): Context {
        val code = currentLanguage(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(code))
        return base.createConfigurationContext(config)
    }

    private fun languageCodes(context: Context): List<String> =
        context.resources.getStringArray(com.zyz4.gkme.R.array.app_language_codes)
            .filter { it.isNotBlank() }

    /** Resolves [SYSTEM_LANGUAGE] against the current system locale list. */
    private fun resolveSystemLanguage(context: Context): String {
        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            val match = matchSupportedLanguage(locales[index])
            if (match != null) return match
        }
        return SOURCE_LANGUAGE
    }

    private fun matchSupportedLanguage(locale: Locale): String? {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "en" -> "en"
            "zh" -> when {
                locale.country.equals("HK", true) || locale.country.equals("MO", true) -> "zh-HK"
                locale.country.equals("TW", true) || locale.script.equals("Hant", true) -> "zh-TW"
                else -> "zh"
            }
            else -> null
        }
    }
}
