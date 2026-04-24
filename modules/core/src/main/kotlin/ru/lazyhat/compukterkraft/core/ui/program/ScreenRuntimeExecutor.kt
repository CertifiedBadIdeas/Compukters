package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position

/**
 * Runtime-side counterpart to [ScreenProgram].
 *
 * Per render tick the executor:
 *
 * 1. Iterates [ScreenProgram.frames] in order.
 * 2. For each frame, evaluates (at most once) `visible` to decide skip/draw
 *    and `origin` to translate the baked relative coordinates of its ops.
 * 3. Evaluates op-level [ru.lazyhat.compukterkraft.core.ui.foundation.ValueExpression]s
 *    (text, snapshots) in place while emitting backend calls.
 *
 * Hit-testing walks [ScreenProgram.hitRegions] in compile-time z-order
 * (descending), evaluating the owning frame's origin/visibility as needed.
 *
 * No allocations, no map lookups, no recursion.
 */
class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
) {
    /**
     * Whether the focusable element (if any) currently has input focus.
     *
     * Toggled by [mouseClicked]: a click inside [ScreenProgram.focusRegion]
     * sets it to `true`; a click that lands outside every hit/focus region
     * clears it. When there is no focus region at all, the flag stays `false`
     * and no key/char events are routed.
     */
    var isFocused: Boolean = false
        private set

    /**
     * The tooltip text under the cursor after the most recent [updateMouse]
     * call, or `null` if the cursor is not over any tooltip region. Host
     * screens typically render this via their platform tooltip API.
     */
    var activeTooltip: String? = null
        private set

    /**
     * Updates every [ru.lazyhat.compukterkraft.core.ui.foundation.HoverState]
     * bound via `Modifier.hoverable(...)` based on the supplied mouse
     * position. Call this from the host screen before [render] so that
     * [ValueExpression]s observed during drawing see the current hover flag.
     *
     * Passing `Int.MIN_VALUE` for either coordinate clears all hover states
     * (useful when the cursor is known to be outside the screen).
     */
    fun updateMouse(
        mouseX: Int,
        mouseY: Int,
    ) {
        for (region in program.hoverRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.evaluate()) {
                region.state.isHovered = false
                continue
            }
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            region.state.isHovered =
                mouseX >= rx && mouseY >= ry && mouseX < rx + region.width && mouseY < ry + region.height
        }

        activeTooltip = null
        for (region in program.tooltipRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.evaluate()) continue
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (mouseX >= rx && mouseY >= ry && mouseX < rx + region.width && mouseY < ry + region.height) {
                activeTooltip = region.text.evaluate()
                break
            }
        }
    }

    fun render(backend: RenderBackend) {
        for (frame in program.frames) {
            if (frame.visible != null && !frame.visible.evaluate()) continue
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val ox = origin.x
            val oy = origin.y
            for (op in frame.ops) {
                when (op) {
                    is RenderOp.FillRect -> {
                        backend.fillRect(op.x + ox, op.y + oy, op.width, op.height, op.color)
                    }

                    is RenderOp.DrawText -> {
                        backend.drawText(op.x + ox, op.y + oy, op.value.evaluate(), op.color)
                    }

                    is RenderOp.DrawTerminalSurface -> {
                        backend.drawTerminalSurface(op.x + ox, op.y + oy, op.snapshot.evaluate())
                    }

                    is RenderOp.DrawCanvas -> {
                        canvasScope.bind(backend, op.x + ox, op.y + oy, op.width, op.height)
                        op.onDraw.invoke(canvasScope)
                    }
                }
            }
        }
    }

    private val canvasScope = OffsetCanvasScope()

    private class OffsetCanvasScope : CanvasScope {
        private var backend: RenderBackend? = null
        private var originX: Int = 0
        private var originY: Int = 0
        override var width: Int = 0
            private set
        override var height: Int = 0
            private set

        fun bind(
            backend: RenderBackend,
            originX: Int,
            originY: Int,
            width: Int,
            height: Int,
        ) {
            this.backend = backend
            this.originX = originX
            this.originY = originY
            this.width = width
            this.height = height
        }

        override fun fillRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Color,
        ) {
            backend?.fillRect(originX + x, originY + y, width, height, color)
        }
    }

    fun mouseClicked(
        x: Int,
        y: Int,
    ): Boolean {
        for (region in program.hitRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.evaluate()) continue
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (x >= rx && y >= ry && x < rx + region.width && y < ry + region.height) {
                region.onClick.invoke()
                return true
            }
        }

        val focus = program.focusRegion
        if (focus != null) {
            val frame = program.frames[focus.frameIndex]
            val frameVisible = frame.visible?.evaluate() ?: true
            if (frameVisible) {
                val origin = frame.origin?.evaluate() ?: Position.Zero
                val fx = focus.x + origin.x
                val fy = focus.y + origin.y
                if (x >= fx && y >= fy && x < fx + focus.width && y < fy + focus.height) {
                    isFocused = true
                    return true
                }
            }
        }
        isFocused = false
        return false
    }

    fun keyPressed(keyCode: Int): Boolean {
        if (!isFocused) return false
        return program.keyHandler?.onKeyPressed?.invoke(keyCode) ?: false
    }

    fun keyReleased(keyCode: Int): Boolean {
        if (!isFocused) return false
        return program.keyHandler?.onKeyReleased?.invoke(keyCode) ?: false
    }

    fun charTyped(ch: Char): Boolean {
        if (!isFocused) return false
        return program.keyHandler?.onCharTyped?.invoke(ch) ?: false
    }
}
