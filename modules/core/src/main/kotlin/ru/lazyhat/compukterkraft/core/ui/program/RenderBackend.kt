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
}
