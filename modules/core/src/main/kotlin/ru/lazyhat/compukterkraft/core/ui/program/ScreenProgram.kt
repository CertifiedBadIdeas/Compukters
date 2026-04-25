package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.HoverState
import ru.lazyhat.compukterkraft.core.ui.foundation.Value
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * A compiled UI description ready for execution.
 *
 * The runtime contract is intentionally thin: render/hit-test walk a flat list
 * of [RenderFrame]s with baked, relative coordinates. Dynamic values enter the
 * pipeline only through [Value]s (e.g. frame origin, visibility,
 * op-level text/snapshot).
 *
 * This program is produced once per content by [ScreenProgramCompiler]. It is
 * expected to be recompiled only when the structural input changes (e.g. the
 * screen's root size).
 */
data class ScreenProgram(
    val frames: List<RenderFrame>,
    val hitRegions: List<HitRegion>,
    val hoverRegions: List<HoverRegion> = emptyList(),
    val tooltipRegions: List<TooltipRegion> = emptyList(),
    val focusNodes: List<FocusNode> = emptyList(),
)

/**
 * A keyboard-focus target produced by a `Modifier.focusable(...)` or by an
 * element that auto-claims focus (e.g. [ru.lazyhat.compukterkraft.core.ui.foundation.UiElement.TerminalSurface]).
 *
 *  - [nodeId] is stable across recompiles and is what the runtime stores
 *    to remember which node currently owns focus.
 *  - [tabOrder] drives Tab/Shift+Tab cycling: nodes are visited in
 *    ascending order; nodes with the same order are visited in
 *    compile-time order; nodes with negative order are skipped by
 *    cycling but can still acquire focus via mouse click.
 *  - [frameIndex] + bounds + [handler] follow the same baked relative
 *    coordinate scheme as [HitRegion].
 */
data class FocusNode(
    val nodeId: String,
    val frameIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val tabOrder: Int,
    val handler: FocusHandler,
)

/**
 * Keyboard/char event handlers for a focused element. All three lambdas
 * return `true` if they consumed the event.
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
    val origin: Value<Position>? = null,
    val visible: Value<Boolean>? = null,
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
        val value: Value<String>,
        val color: Color,
    ) : RenderOp

    data class DrawTerminalSurface(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val snapshot: Value<ScreenBufferSnapshot>,
    ) : RenderOp

    data class DrawCanvas(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val onDraw: CanvasScope.() -> Unit,
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

/**
 * A rectangle whose [HoverState] flag is updated each render tick based on
 * mouse position. Coordinates follow the same baked/frame-relative scheme
 * as [HitRegion].
 */
data class HoverRegion(
    val nodeId: String,
    val frameIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val state: HoverState,
)

/**
 * A rectangle whose owning element's tooltip should be shown while the
 * mouse hovers over it. [text] is evaluated lazily once per render tick
 * (only for the region that actually contains the cursor).
 */
data class TooltipRegion(
    val nodeId: String,
    val frameIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: Value<String>,
)
