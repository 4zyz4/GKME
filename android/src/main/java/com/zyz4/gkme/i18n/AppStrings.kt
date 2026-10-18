package com.zyz4.gkme.i18n

import com.zyz4.gkme.R
import android.content.Context
import androidx.annotation.StringRes

/**
 * Resource access for context-less helpers (protocol managers, renderers, enums).
 * Normal UI code should call `getString(R.string.…)` / `context.getString(R.string.…)`
 * directly; this object only bridges code that has no Context parameter.
 */
object AppStrings {

    @Volatile
    private var appContext: Context? = null

    /**
     * Keeps the exact [context] (which may be a locale-overridden configuration context from
     * [AppLocale.wrap]). Calling `applicationContext` here would silently drop that override,
     * leaving context-less strings on the language chosen at process start.
     */
    fun init(context: Context) {
        appContext = context
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val context = appContext ?: return ""
        if (args.isEmpty()) return context.getString(id)
        return context.getString(id, *args.map { it ?: "" }.toTypedArray())
    }
}
