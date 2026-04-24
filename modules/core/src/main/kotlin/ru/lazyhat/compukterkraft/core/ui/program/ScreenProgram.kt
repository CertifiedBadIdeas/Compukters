package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.ValueExpression
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position

/**
 * A compiled UI description ready for execution.
 *
 * The runtime contract is intentionally thin: render/hit-test walk a flat list
 * of [RenderFrame]s with baked, relative coordinates. Dynamic values enter the
 * pipeline only through [ValueExpression]s (e.g. frame origin, visibility,
 * op-level text/snapshot).
 *
 * This program is produced once per content by [ScreenProgramCompiler]. It is
 * expected to be recompiled only when the structural input changes (e.g. the
 * screen's root size).
 */
data class ScreenProgram(
    val frames: List<RenderFrame>,
    val hitRegions: List<HitRegion>,
    val focusedNodeId: String? = null,
    val focusRegion: FocusRegion? = null,
    val keyHandler: FocusHandler? = null,
)

/**
 * Focus acquisition bounds for the single focusable element in a screen.
 *
 * When a click lands inside this region the runtime sets the screen's focus
 * flag to `true` (and the click is considered consumed). Clicks outside any
 * hit region clear the focus flag without being consumed.
 */
data class FocusRegion(
    val nodeId: String,
    val frameIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Keyboard/char event handlers for the currently focused element.
 *
 * All three lambdas return `true` if they consumed the event. They are
 * invoked only while the runtime's focus flag is `true`.
 */
data class FocusHandler(
    val onKeyPressed: (Int) -> Boolean = { false },
    val onKeyReleased: (Int) -> Boolean = { false },
    val onCharTyped: (Char) -> Boolean = { false },
)

/**
 * A group of [RenderOp]s that share an optional dynamic [origin] and
 * [visible] expression.
 *
 * When [origin] is `null`, ops are rendered at their baked absolute
 * coordinates. When non-null, the origin is evaluated once per render tick and
 * added to every op's baked relative coordinates.
 *
 * When [visible] is `null`, the frame is always drawn. Otherwise the
 * expression is evaluated once per render tick.
 */
data class RenderFrame(
    val origin: ValueExpression<Position>? = null,
    val visible: ValueExpression<Boolean>? = null,
    val ops: List<RenderOp>,
)

/**
 * A drawing primitive bound to absolute pixels when [RenderFrame.origin] is
 * `null`, or to pixels relative to the frame origin otherwise.
 */
sealed interface RenderOp {
    data class FillRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val color: Color,
    ) : RenderOp

    data class DrawText(
        val x: Int,
        val y: Int,
        val value: ValueExpression<String>,
        val color: Color,
    ) : RenderOp

    data class DrawTerminalSurface(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val snapshot: ValueExpression<Any?>,
    ) : RenderOp
}

/**
 * A clickable area.
 *
 * [frameIndex] refers into [ScreenProgram.frames]; the region inherits its
 * frame's origin/visibility so popups/overlays remain clickable wherever they
 * are drawn.
 *
 * Coordinates are baked relative to the parent frame's origin.
 */
data class HitRegion(
    val nodeId: String,
    val frameIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val zIndex: Int,
    val onClick: () -> Unit,
)
