package com.zyz4.gkme.data

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers whether the user accepted the GPLv3 license agreement.
 *
 * The rest of the app settings live in an asynchronous DataStore, but this flag has to be
 * resolved synchronously in `MainActivity.onCreate` before the main UI is built (and before
 * any connection service can start). A small SharedPreferences file keeps that check cheap.
 */
object LicenseAgreementStore {

    private const val PREFS_NAME = "license_agreement"
    private const val KEY_GPL3_ACCEPTED = "gpl3_accepted"

    fun isAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GPL3_ACCEPTED, false)

    fun accept(context: Context) {
        // commit() makes sure the flag is durable before the gamepad UI and services start.
        prefs(context).edit(commit = true) { putBoolean(KEY_GPL3_ACCEPTED, true) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
