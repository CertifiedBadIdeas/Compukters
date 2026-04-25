package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.TickContext
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position

/**
 * Runtime-side counterpart to [ScreenProgram].
 *
 * Per render tick the executor:
 *
 * 1. Iterates [ScreenProgram.frames] in order.
 * 2. For each frame, evaluates (at most once) `visible` to decide skip/draw
 *    and `origin` to translate the baked relative coordinates of its ops.
 * 3. Evaluates op-level [ru.lazyhat.compukterkraft.core.ui.foundation.Value]s
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
     * Identifier of the focus node that currently owns keyboard focus, or
     * `null` if no node is focused.
     *
     *  - Set by [mouseClicked] when a click lands inside a focus node.
     *  - Cleared by [mouseClicked] when a click lands outside every focus
     *    node (and outside every hit region).
     *  - Updated by [keyPressed] when Tab/Shift+Tab cycles focus.
     *  - Restored by [restoreFocus] after the program is recompiled.
     */
    var focusedNodeId: String? = null
        private set

    /**
     * Convenience flag mirroring "is *anything* focused right now". Kept for
     * call sites that only care whether the DSL is currently absorbing
     * keyboard input.
     */
    val isFocused: Boolean
        get() = focusedNodeId != null

    /**
     * Restores the focused node identifier after the executor has been
     * rebuilt because of a layout-driven recompile. The id is matched against
     * the new program's focus nodes; if no node with that id exists, focus
     * is cleared.
     */
    fun restoreFocus(nodeId: String?) {
        focusedNodeId =
            if (nodeId != null && program.focusNodes.any { it.nodeId == nodeId }) {
                nodeId
            } else {
                null
            }
    }

    /**
     * The tooltip text under the cursor after the most recent [updateMouse]
     * call, or `null` if the cursor is not over any tooltip region. Host
     * screens typically render this via their platform tooltip API.
     */
    var activeTooltip: String? = null
        private set

    /**
     * Monotonic counter incremented just before every [render] call. The
     * value is also published into [TickContext] so [tickValue] expressions
     * see the same tick the runtime is rendering.
     */
    private var tickCounter: Int = 0

    /**
     * The hit region currently being dragged, set by [mouseClicked] when the
     * pressed region carries any drag handler and cleared by [mouseReleased].
     */
    private var activeDragRegion: HitRegion? = null

    /**
     * Updates every [ru.lazyhat.compukterkraft.core.ui.foundation.HoverState]
     * bound via `Modifier.hoverable(...)` based on the supplied mouse
     * position. Call this from the host screen before [render] so that
     * [ru.lazyhat.compukterkraft.core.ui.foundation.Value]s observed during drawing see the current hover flag.
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
            if (frame.visible != null && !frame.visible.value) {
                region.state.isHovered = false
                continue
            }
            val origin = frame.origin?.value ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            region.state.isHovered =
                mouseX >= rx && mouseY >= ry && mouseX < rx + region.width && mouseY < ry + region.height
        }

        activeTooltip = null
        for (region in program.tooltipRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.value) continue
            val origin = frame.origin?.value ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (mouseX >= rx && mouseY >= ry && mouseX < rx + region.width && mouseY < ry + region.height) {
                activeTooltip = region.text.value
                break
            }
        }
    }

    fun render(backend: RenderBackend) {
        TickContext.current = ++tickCounter
        for (frame in program.frames) {
            if (frame.visible != null && !frame.visible.value) continue
            val origin = frame.origin?.value ?: Position.Zero
            val ox = origin.x
            val oy = origin.y
            for (op in frame.ops) {
                when (op) {
                    is RenderOp.FillRect -> {
                        backend.fillRect(op.x + ox, op.y + oy, op.width, op.height, op.color)
                    }

                    is RenderOp.DrawText -> {
                        backend.drawText(op.x + ox, op.y + oy, op.value.value, op.color)
                    }

                    is RenderOp.DrawTerminalSurface -> {
                        backend.drawTerminalSurface(op.x + ox, op.y + oy, op.snapshot.value)
                    }

                    is RenderOp.DrawCanvas -> {
                        canvasScope.bind(backend, op.x + ox, op.y + oy, op.width, op.height)
                        op.onDraw.invoke(canvasScope)
                    }

                    is RenderOp.PushClip -> {
                        backend.pushClip(op.x + ox, op.y + oy, op.width, op.height)
                    }

                    RenderOp.PopClip -> {
                        backend.popClip()
                    }

                    is RenderOp.DrawCodeEditor -> {
                        backend.drawCodeEditor(
                            op.x + ox,
                            op.y + oy,
                            op.width,
                            op.height,
                            op.viewModel.value,
                            op.fontWidth,
                            op.fontHeight,
                        )
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
            if (frame.visible != null && !frame.visible.value) continue
            val origin = frame.origin?.value ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (x >= rx && y >= ry && x < rx + region.width && y < ry + region.height) {
                // Honour any clip rectangle (e.g. ScrollArea viewport) to
                // ignore clicks on parts of the region scrolled out of view.
                val clip = region.clip
                if (clip != null) {
                    val clipFrame = program.frames[clip.frameIndex]
                    if (clipFrame.visible != null && !clipFrame.visible.value) continue
                    val clipOrigin = clipFrame.origin?.value ?: Position.Zero
                    val cx = clip.x + clipOrigin.x
                    val cy = clip.y + clipOrigin.y
                    if (x < cx || y < cy || x >= cx + clip.width || y >= cy + clip.height) continue
                }
                region.onClick.invoke()
                region.onClickAt?.invoke(x - rx, y - ry)
                if (region.onDragStart != null || region.onDrag != null || region.onDragEnd != null) {
                    activeDragRegion = region
                    region.onDragStart?.invoke(x, y)
                }
                // If the clicked region also has a matching focus node,
                // make it the focused one so subsequent key events route there.
                program.focusNodes
                    .firstOrNull { it.nodeId == region.nodeId }
                    ?.let { focusedNodeId = it.nodeId }
                return true
            }
        }

        for (focus in program.focusNodes) {
            val frame = program.frames[focus.frameIndex]
            if (frame.visible != null && !frame.visible.value) continue
            val origin = frame.origin?.value ?: Position.Zero
            val fx = focus.x + origin.x
            val fy = focus.y + origin.y
            if (x >= fx && y >= fy && x < fx + focus.width && y < fy + focus.height) {
                focusedNodeId = focus.nodeId
                return true
            }
        }
        focusedNodeId = null
        return false
    }

    fun mouseDragged(
        x: Int,
        y: Int,
    ): Boolean {
        val region = activeDragRegion ?: return false
        region.onDrag?.invoke(x, y)
        return true
    }

    fun mouseReleased(
        x: Int,
        y: Int,
    ): Boolean {
        val region = activeDragRegion ?: return false
        region.onDragEnd?.invoke(x, y)
        activeDragRegion = null
        return true
    }

    fun mouseScrolled(
        x: Int,
        y: Int,
        deltaY: Double,
    ): Boolean {
        for (region in program.scrollRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.value) continue
            val origin = frame.origin?.value ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (x >= rx && y >= ry && x < rx + region.width && y < ry + region.height) {
                if (region.onScroll(deltaY)) return true
            }
        }
        return false
    }

    private fun focusedHandler(): FocusHandler? {
        val id = focusedNodeId ?: return null
        return program.focusNodes.firstOrNull { it.nodeId == id }?.handler
    }

    fun keyPressed(
        keyCode: Int,
        modifiers: Int = 0,
    ): Boolean {
        val handler = focusedHandler() ?: return false
        if (handler.onKeyPressed.invoke(keyCode)) return true
        if (keyCode == KEY_TAB) {
            return cycleFocus(forward = (modifiers and MOD_SHIFT) == 0)
        }
        return false
    }

    fun keyReleased(keyCode: Int): Boolean {
        val handler = focusedHandler() ?: return false
        return handler.onKeyReleased.invoke(keyCode)
    }

    fun charTyped(ch: Char): Boolean {
        val handler = focusedHandler() ?: return false
        return handler.onCharTyped.invoke(ch)
    }

    /**
     * Advances keyboard focus to the next (or previous) focusable node by
     * compile-time tab order. Negative [FocusNode.tabOrder] values opt out
     * of cycling. Returns `true` if focus was moved.
     */
    private fun cycleFocus(forward: Boolean): Boolean {
        val tabbable =
            program.focusNodes
                .asSequence()
                .filter { it.tabOrder >= 0 }
                .filter {
                    val frame = program.frames[it.frameIndex]
                    frame.visible?.value ?: true
                }.toList()
                .let { list ->
                    val sorted = list.sortedBy { it.tabOrder }
                    if (forward) sorted else sorted.asReversed()
                }
        if (tabbable.isEmpty()) return false
        val currentIndex = tabbable.indexOfFirst { it.nodeId == focusedNodeId }
        val next = tabbable[(currentIndex + 1).mod(tabbable.size)]
        if (next.nodeId == focusedNodeId) return false
        focusedNodeId = next.nodeId
        return true
    }

    private companion object {
        // GLFW key/modifier constants. Re-declared here to keep the runtime
        // independent of the GLFW dependency.
        const val KEY_TAB = 258
        const val MOD_SHIFT = 0x0001
    }
}
