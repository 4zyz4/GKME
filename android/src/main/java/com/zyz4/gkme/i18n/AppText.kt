package com.zyz4.gkme.i18n

import androidx.annotation.StringRes

/**
 * A UI string that is resolved lazily instead of being frozen at the moment it is produced.
 *
 * State holders (connection status, host status, …) outlive Activity re-creation, so storing an
 * already-formatted [String] would keep showing the previous language after the user switches it.
 * Keeping a resource reference and resolving it whenever the value is rendered makes the existing
 * state follow language changes without waiting for the next state update.
 */
sealed interface AppText {

    fun resolve(): String

    data class Res(@StringRes val id: Int, val args: List<Any?> = emptyList()) : AppText {
        override fun resolve(): String = AppStrings.get(id, *args.toTypedArray())
    }

    data class Literal(val value: String) : AppText {
        override fun resolve(): String = value
    }

    data class Provider(val provider: () -> String) : AppText {
        override fun resolve(): String = provider()
    }
}
