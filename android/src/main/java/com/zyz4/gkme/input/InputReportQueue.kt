package com.zyz4.gkme.input

internal data class InputReport(val keyboard: KeyboardReport, val mouse: MouseReport)

/** One ordered stream preserves Ctrl+click, short clicks and keyboard transitions between ticks. */
internal class InputReportQueue {
    private var keyboard = KeyboardReport()
    private var mouseButtons = 0
    private val pending = ArrayDeque<InputReport>()

    @Synchronized
    fun keyboard(report: KeyboardReport) {
        if (keyboard == report) return
        keyboard = report
        enqueue(InputReport(keyboard, MouseReport(mouseButtons)))
    }

    @Synchronized
    fun mouse(report: MouseReport) {
        val previous = pending.lastOrNull()
        if (previous != null && previous.keyboard == keyboard && mouseButtons == report.buttons) {
            pending.removeLast()
            pending.addLast(InputReport(keyboard, report.copy(
                dx = previous.mouse.dx + report.dx, dy = previous.mouse.dy + report.dy,
                wheel = previous.mouse.wheel + report.wheel, pan = previous.mouse.pan + report.pan)))
        } else {
            enqueue(InputReport(keyboard, report))
        }
        mouseButtons = report.buttons
    }

    private fun enqueue(report: InputReport) {
        if (pending.size >= MAX_PENDING_REPORTS) pending.removeFirst()
        pending.addLast(report)
    }

    @Synchronized
    fun drain(): List<InputReport> {
        if (pending.isEmpty()) return listOf(InputReport(keyboard, MouseReport(mouseButtons)))
        return pending.toList().also { pending.clear() }
    }

    @Synchronized
    fun reset(currentKeyboard: KeyboardReport) {
        keyboard = currentKeyboard
        mouseButtons = 0
        pending.clear()
    }

    private companion object {
        const val MAX_PENDING_REPORTS = 256
    }
}
