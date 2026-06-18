package ru.lazyhat.kraftui.foundation

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

    /**
     * Measure the rendered pixel width of [text] under the platform's font metrics. Used by
     * canvas-drawn overlays that need to align with text rendered by other elements (e.g.
     * remote-collaborator carets above a code editor). Implementations that lack a real font
     * fall back to a fixed-width approximation.
     */
    fun measureText(text: String): Int
}
