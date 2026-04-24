package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.vt.VtSink

/**
 * Lightweight [VtSink] that tracks only the cursor position on an unbounded
 * abstract grid. Used by [VmTerminalApi.readLine] after the server-side
 * [ScreenBuffer][ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBuffer]
 * was removed — we still need to know how far the cursor advanced while
 * printing a prompt, but no actual buffer is maintained.
 *
 * Coordinates are 0-based. There is no clamping; X can grow unboundedly
 * until a CR/LF resets it.
 */
class CursorTracker : VtSink {
    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set

    private var savedX: Int = 0
    private var savedY: Int = 0

    override fun printChar(ch: Char) {
        cursorX += 1
    }

    override fun moveCursor(row: Int?, col: Int?) {
        cursorY = (row ?: 1) - 1
        cursorX = (col ?: 1) - 1
    }

    override fun cursorRelative(deltaRows: Int, deltaCols: Int) {
        cursorY += deltaRows
        cursorX += deltaCols
        if (cursorX < 0) cursorX = 0
        if (cursorY < 0) cursorY = 0
    }

    override fun eraseDisplay(mode: Int) = Unit
    override fun eraseLine(mode: Int) = Unit
    override fun setForegroundColor(color: Int) = Unit
    override fun setBackgroundColor(color: Int) = Unit
    override fun resetAttributes() = Unit

    override fun saveCursor() {
        savedX = cursorX
        savedY = cursorY
    }

    override fun restoreCursor() {
        cursorX = savedX
        cursorY = savedY
    }

    override fun lineFeed() {
        // Match ScreenBufferVtSink: LF behaves as CR+LF (move to column 0 of
        // the next row). This is what the VM's client-side ScreenBuffer does,
        // so the tracker must follow suit — otherwise readLine's cursor
        // arithmetic drifts after any printLine and backspace emits CSI H
        // coordinates that send the cursor into unrelated lines of the log.
        cursorY += 1
        cursorX = 0
    }

    override fun carriageReturn() {
        cursorX = 0
    }

    override fun backspace() {
        if (cursorX > 0) cursorX -= 1
    }
}
