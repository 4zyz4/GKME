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
 * calls resolve to the matching `values-xx` directory.
 */
object AppLocale {

    const val SOURCE_LANGUAGE = "zh"
    private const val PREFS_NAME = "i18n_prefs"
    private const val KEY_LANGUAGE = "language"

    fun currentLanguage(context: Context): String {
        val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SOURCE_LANGUAGE) ?: SOURCE_LANGUAGE
        return if (availableLanguages(context).any { it.code == code }) code else SOURCE_LANGUAGE
    }

    fun availableLanguages(context: Context): List<LanguageOption> {
        val res = context.resources
        val codes = res.getStringArray(com.zyz4.gkme.R.array.app_language_codes)
        val labels = res.getStringArray(com.zyz4.gkme.R.array.app_language_labels)
        return codes.mapIndexed { index, code ->
            LanguageOption(code, labels.getOrElse(index) { code })
        }.filter { it.code.isNotBlank() }
    }

    /**
     * Persists [code] and refreshes the application-level context used by [AppStrings].
     * Returns true when the language actually changed; callers should recreate the activity.
     */
    fun setLanguage(context: Context, code: String): Boolean {
        if (code == currentLanguage(context)) return false
        if (availableLanguages(context).none { it.code == code }) return false
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
}
