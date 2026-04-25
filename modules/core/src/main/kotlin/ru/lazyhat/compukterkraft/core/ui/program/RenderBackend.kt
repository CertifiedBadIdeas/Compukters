package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

interface RenderBackend {
    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Color,
    )

    fun drawText(
        x: Int,
        y: Int,
        text: String,
        color: Color,
    )

    fun drawTerminalSurface(
        x: Int,
        y: Int,
        snapshot: ScreenBufferSnapshot,
    )

    /**
     * Pushes a rectangular clip region onto an internal clip stack. Subsequent
     * draw calls are restricted to the intersection of all currently pushed
     * clips. Implementations are expected to support arbitrary nesting.
     */
    fun pushClip(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /** Pops the most recently pushed clip region. */
    fun popClip()
}
