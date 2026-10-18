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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val context = appContext ?: return ""
        if (args.isEmpty()) return context.getString(id)
        return context.getString(id, *args.map { it ?: "" }.toTypedArray())
    }
}
