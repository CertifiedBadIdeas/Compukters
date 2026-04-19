package ru.lazyhat.compukterkraft.core.ui.program

interface RenderBackend {
    fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int)

    fun drawText(x: Int, y: Int, text: String, color: Int)

    fun drawTerminalSurface(x: Int, y: Int, snapshot: Any?)
}
