package ru.lazyhat.compukterkraft.core.ui.foundation

/**
 * Scope exposed to [UiElement.Canvas] draw lambdas. Coordinates passed to
 * [fillRect] are local to the canvas: `(0, 0)` is the canvas's top-left
 * corner. The executor translates every call into absolute pixels before
 * delegating to its render backend.
 *
 * Canvas exists for pixel-precise drawings (small icons, patterns) that are
 * awkward to express as nested `box` + `background` children.
 */
interface CanvasScope {
    val width: Int
    val height: Int

    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Color,
    )
}
