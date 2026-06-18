package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.CanvasScope
import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.TickContext
import ru.lazyhat.kraftui.foundation.modifier.Position
import ru.lazyhat.kraftui.foundation.modifier.TextAlignment

/**
 * Runtime-side counterpart to [ScreenProgram].
 *
 * Per render tick the executor:
 *
 * 1. Iterates [ScreenProgram.frames] in order.
 * 2. For each frame, evaluates (at most once) `visible` to decide skip/draw
 *    and `origin` to translate the baked relative coordinates of its ops.
 * 3. Evaluates op-level [ru.lazyhat.kraftui.foundation.Value]s
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
     * The first focusable node id in compile order (lowest tab order
     * preferred), or `null` if the program declares no focusables. Useful
     * for hosts that want a sensible default focus when the user hasn't
     * clicked yet.
     */
    fun firstFocusableNodeId(): String? =
        program.focusNodes
            .filter { it.tabOrder >= 0 }
            .minByOrNull { it.tabOrder }
            ?.nodeId

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
     * Updates every [ru.lazyhat.kraftui.foundation.HoverState]
     * bound via `Modifier.hoverable(...)` based on the supplied mouse
     * position. Call this from the host screen before [render] so that
     * [ru.lazyhat.kraftui.foundation.Value]s observed during drawing see the current hover flag.
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
                        val text = op.value.value
                        val textWidth = backend.measureText(text)
                        val textX =
                            when (op.alignment) {
                                TextAlignment.Start -> op.x
                                TextAlignment.Center -> op.x + (op.width - textWidth) / 2
                                TextAlignment.End -> op.x + op.width - textWidth
                            }
                        backend.drawText(textX + ox, op.y + oy, text, op.color.value)
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

        override fun measureText(text: String): Int = backend?.measureText(text) ?: 0
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
                // Update focus *before* running the click handler. The
                // handler may synchronously mutate state that triggers a
                // host-side `invalidate()` (e.g. via a StateFlow collector
                // dispatched on the main thread), which captures
                // `focusedNodeId` for restoration on the next rebuild. If
                // we set focus *after* `onClick`, that capture would see the
                // stale (pre-click) focus and the new focus assignment would
                // land on an executor instance that is about to be discarded
                // — typing right after the click would then have no effect.
                program.focusNodes
                    .firstOrNull { it.nodeId == region.nodeId }
                    ?.let { focusedNodeId = it.nodeId }
                region.onClick.invoke()
                region.onClickAt?.invoke(x - rx, y - ry)
                if (region.onDragStart != null || region.onDrag != null || region.onDragEnd != null) {
                    activeDragRegion = region
                    region.onDragStart?.invoke(x, y)
                }
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
        if (handler.onKeyPressed.invoke(keyCode, modifiers)) return true
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
