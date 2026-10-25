package com.orson391.virtualgamepad

import android.os.Bundle
import rikka.shizuku.ShizukuProvider

/**
 * Extends Shizuku's [ShizukuProvider] with synchronization on [call].
 *
 * The Shizuku server may dispatch binders concurrently across multiple threads during app startup.
 * Shizuku's `onBinderReceived` is not internally synchronized, which can cause duplicate `unlinkToDeath`
 * calls throwing `NoSuchElementException` and leading to failed binder delivery.
 *
 * Serializing `call` here prevents this race condition by allowing the first call to register the binder
 * while subsequent calls return early via `pingBinder()`.
 */
class GkmeShizukuProvider : ShizukuProvider() {

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        super.call(method, arg, extras)
}
