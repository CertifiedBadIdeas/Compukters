package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.editor.EditorViewModel
import ru.lazyhat.kraftui.foundation.Color

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
        snapshot: Any,
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

    /**
     * Draws a code editor at `(x, y)` with the given [width]/[height]. The
     * backend renders text, gutter, cursor, highlights and diagnostics from
     * [viewModel], using [fontWidth]/[fontHeight] as the monospace glyph
     * size.
     */
    fun drawCodeEditor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        viewModel: EditorViewModel,
        fontWidth: Int,
        fontHeight: Int,
    )

    /**
     * Returns the rendered width of [text] in pixels. Used by canvas overlays that must align
     * with text rendered through [drawText] / [drawCodeEditor]. Backends with a real font use
     * the font's variable-width metrics; in-memory test backends typically return
     * `text.length * defaultFontWidth`.
     */
    fun measureText(text: String): Int
}
