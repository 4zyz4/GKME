package com.zyz4.gkme

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import com.zyz4.gkme.input.ExternalMousePassthrough

/** Coordinates shared pointer capture without changing the controller touchpad path. */
internal class ExternalMouseController(private val activity: MainActivity) {
    private var enabled = false
    private val diagnostics = ExternalMouseDiagnostics()

    private fun diagnosticState(): String = with(activity) {
        "enabled=$enabled captured=${gamepadLayout.hasPointerCapture()} focus=${hasWindowFocus()} " +
            "lifecycle=${lifecycle.currentState} settings=$inSettings edit=${gamepadLayout.isEditModeActive()} " +
            "canForward=${viewModel.connectionManager.canForwardExternalInput}"
    }

    fun renderDiagnostics(): String = diagnostics.render(diagnosticState())

    fun install() {
        activity.gamepadLayout.onExternalMouseEvent = ::onCapturedEvent
        activity.gamepadLayout.onExternalMouseCaptureChanged = { captured ->
            if (BuildConfig.DEBUG) diagnostics.captureChanged(captured)
            if (!captured) activity.viewModel.externalMouse.releaseAll()
        }
    }

    fun updateCapture(paused: Boolean = false) {
        val a = activity
        val focused = !paused && a.hasWindowFocus() &&
            a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val hasMouse = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { device ->
                ExternalMousePassthrough.isExternalMouse(InputDevice.SOURCE_MOUSE, device.sources,
                    device.isExternal, device.isVirtual, MotionEvent.TOOL_TYPE_MOUSE)
            } == true
        }
        val want = focused && a.viewModel.connectionManager.canForwardExternalInput &&
            !a.inSettings && !a.gamepadLayout.isEditModeActive() && hasMouse
        if (!want) a.viewModel.externalMouse.releaseAll()
        enabled = want
        // Legacy controller touchpad capture shares the same View. Do not turn it off
        // when a mouse disconnects, nor let its callback turn off active mouse capture.
        val controllerCapture = focused && a.pointerCaptureNeeded && !a.inSettings &&
            !a.gamepadLayout.isEditModeActive()
        a.gamepadLayout.setTouchpadCaptureMode(want || controllerCapture)
    }

    private fun canForward(): Boolean = enabled && activity.hasWindowFocus() &&
        !activity.inSettings && !activity.gamepadLayout.isEditModeActive() &&
        activity.viewModel.connectionManager.canForwardExternalInput

    private fun eligible(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        return event.pointerCount > 0 && ExternalMousePassthrough.isExternalMouse(
            event.source, device.sources, device.isExternal, device.isVirtual, event.getToolType(0))
    }

    /** Discard absolute coordinates while the capture request is pending; never hit the screen edge. */
    fun onUncapturedEvent(event: MotionEvent): Boolean {
        if (BuildConfig.DEBUG) diagnostics.record("uncaptured", event, diagnosticState())
        if (!eligible(event)) return false
        updateCapture()
        return canForward()
    }

    private fun onCapturedEvent(event: MotionEvent): Boolean {
        if (BuildConfig.DEBUG) diagnostics.record("captured", event, diagnosticState())
        if (enabled && event.device == null && event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            activity.viewModel.externalMouse.releaseDevice(event.deviceId)
            return true
        }
        if (!eligible(event)) return false
        if (!canForward()) {
            if (!enabled) return false
            activity.viewModel.externalMouse.releaseAll()
            return true
        }
        val mouse = activity.viewModel.externalMouse
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE ||
            action == MotionEvent.ACTION_SCROLL) {
            for (index in 0 until event.historySize) {
                mouse.handle(event.deviceId, action, event.buttonState,
                    event.getHistoricalX(index), event.getHistoricalY(index),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, index),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_HSCROLL, index))
            }
        }
        return mouse.handle(event.deviceId, action, event.buttonState, event.x, event.y,
            event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getAxisValue(MotionEvent.AXIS_HSCROLL))
    }

    /** Android may also synthesize BACK/FORWARD keys for mouse side buttons. MotionEvent carries them. */
    fun consumeSideButtonKey(event: KeyEvent): Boolean {
        if (!canForward() || !activity.gamepadLayout.hasPointerCapture()) return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_FORWARD) return false
        val device = event.device ?: return false
        return ExternalMousePassthrough.isExternalMouse(event.source, device.sources,
            device.isExternal, device.isVirtual, MotionEvent.TOOL_TYPE_MOUSE)
    }
}
