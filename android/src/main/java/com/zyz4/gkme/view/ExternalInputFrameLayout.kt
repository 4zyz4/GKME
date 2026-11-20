package com.zyz4.gkme.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

/** Offer physical keys before an IME can consume them and replace them with committed text. */
class ExternalInputFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    internal var onExternalKeyPreIme: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (onExternalKeyPreIme?.invoke(event) == true) return true
        return super.dispatchKeyEventPreIme(event)
    }
}
