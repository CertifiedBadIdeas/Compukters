package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.UiElement
import ru.lazyhat.kraftui.foundation.Value
import ru.lazyhat.kraftui.foundation.modifier.TextAlignment
import ru.lazyhat.kraftui.foundation.modifier.Position
import ru.lazyhat.kraftui.foundation.modifier.findBackground
import ru.lazyhat.kraftui.foundation.modifier.findClickable
import ru.lazyhat.kraftui.foundation.modifier.findDraggable
import ru.lazyhat.kraftui.foundation.modifier.findFocusable
import ru.lazyhat.kraftui.foundation.modifier.findHoverable
import ru.lazyhat.kraftui.foundation.modifier.findSize
import ru.lazyhat.kraftui.foundation.modifier.findTextAlignment
import ru.lazyhat.kraftui.foundation.modifier.findTooltip
import ru.lazyhat.kraftui.foundation.modifier.findZIndex

/**
 * Compiles a [UiElement] tree into a [ScreenProgram] with:
 *
 *  - a flat list of [RenderFrame]s, each with baked relative coordinates
 *    and optional dynamic `origin`/`visible` expressions;
 *  - a flat list of [HitRegion]s, sorted by descending z-index, with their
 *    `onClick` handlers bound directly (no string-keyed indirection);
 *  - at most one focused node plus its `onKey` handler.
 *
 * The result is designed to be rebuilt only on structural changes (screen
 * resize, different content). Per-frame rendering and hit-testing avoid
 * allocations and map lookups entirely.
 */
class ScreenProgramCompiler(
    private val fontMetrics: FontMetrics? = null,
) {
    fun compile(
        root: UiElement,
        rootX: Int = 0,
        rootY: Int = 0,
        rootWidth: Int = 0,
        rootHeight: Int = 0,
    ): ScreenProgram {
        val frames = mutableListOf<MutableList<RenderOp>>()
        val descriptors = mutableListOf<FrameDescriptor>()
        val hitRegions = mutableListOf<HitRegion>()
        val hoverRegions = mutableListOf<HoverRegion>()
        val tooltipRegions = mutableListOf<TooltipRegion>()
        val focusNodes = mutableListOf<FocusNode>()
        val scrollRegions = mutableListOf<ScrollRegion>()

        val rootSize = root.modifier.findSize()?.size
        val effectiveRootWidth = if (rootWidth > 0) rootWidth else rootSize?.width ?: 0
        val effectiveRootHeight = if (rootHeight > 0) rootHeight else rootSize?.height ?: 0

        val rootLayout =
            UiLayoutResolver(effectiveRootWidth, effectiveRootHeight, fontMetrics)
                .resolve(root, rootNodeId = "root", rootX = rootX, rootY = rootY)

        val rootOps = mutableListOf<RenderOp>()
        frames += rootOps
        descriptors += FrameDescriptor(origin = null, visible = null)
        lower(
            element = root,
            nodeId = "root",
            layout = rootLayout,
            ops = rootOps,
            hitRegions = hitRegions,
            hoverRegions = hoverRegions,
            tooltipRegions = tooltipRegions,
            frames = frames,
            descriptors = descriptors,
            focusNodes = focusNodes,
            scrollRegions = scrollRegions,
            frameIndex = 0,
        )

        return ScreenProgram(
            frames =
                frames.mapIndexed { index, ops ->
                    val descriptor = descriptors[index]
                    RenderFrame(origin = descriptor.origin, visible = descriptor.visible, ops = ops.toList())
                },
            hitRegions = hitRegions.sortedByDescending { it.zIndex },
            hoverRegions = hoverRegions.toList(),
            tooltipRegions = tooltipRegions.toList(),
            focusNodes = focusNodes.toList(),
            scrollRegions = scrollRegions.toList(),
        )
    }

    private fun lower(
        element: UiElement,
        nodeId: String,
        layout: Map<String, LayoutNode>,
        ops: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        hoverRegions: MutableList<HoverRegion>,
        tooltipRegions: MutableList<TooltipRegion>,
        frames: MutableList<MutableList<RenderOp>>,
        descriptors: MutableList<FrameDescriptor>,
        focusNodes: MutableList<FocusNode>,
        scrollRegions: MutableList<ScrollRegion>,
        frameIndex: Int,
    ) {
        if (element is UiElement.Overlay) {
            lowerOverlay(
                element,
                nodeId,
                layout,
                hitRegions,
                hoverRegions,
                tooltipRegions,
                frames,
                descriptors,
                focusNodes,
                scrollRegions,
            )
            return
        }

        val node = layout[nodeId] ?: return

        // Modifier-based focus claim works on any element (Box, Text, Canvas, ...).
        element.modifier.findFocusable()?.let { f ->
            focusNodes +=
                FocusNode(
                    nodeId = f.id,
                    frameIndex = frameIndex,
                    x = node.x,
                    y = node.y,
                    width = node.width,
                    height = node.height,
                    tabOrder = f.tabOrder,
                    handler =
                        FocusHandler(
                            onKeyPressed = { key, _ -> f.onKeyPressed(key) },
                            onKeyReleased = f.onKeyReleased,
                            onCharTyped = f.onCharTyped,
                        ),
                )
        }
        element.modifier.findHoverable()?.let { hoverable ->
            hoverRegions +=
                HoverRegion(
                    nodeId = nodeId,
                    frameIndex = frameIndex,
                    x = node.x,
                    y = node.y,
                    width = node.width,
                    height = node.height,
                    state = hoverable.state,
                )
        }
        element.modifier.findTooltip()?.let { tooltip ->
            tooltipRegions +=
                TooltipRegion(
                    nodeId = nodeId,
                    frameIndex = frameIndex,
                    x = node.x,
                    y = node.y,
                    width = node.width,
                    height = node.height,
                    text = tooltip.text,
                )
        }
        when (element) {
            is UiElement.Box -> {
                element.modifier.findBackground()?.let { bg ->
                    ops += RenderOp.FillRect(node.x, node.y, node.width, node.height, bg.color)
                }
                val clickable = element.modifier.findClickable()
                val draggable = element.modifier.findDraggable()
                if (clickable != null || draggable != null) {
                    hitRegions +=
                        HitRegion(
                            nodeId = nodeId,
                            frameIndex = frameIndex,
                            x = node.x,
                            y = node.y,
                            width = node.width,
                            height = node.height,
                            zIndex = element.modifier.findZIndex()?.zIndex ?: 0,
                            onClick = clickable?.onClick ?: {},
                            onDragStart = draggable?.onDragStart,
                            onDrag = draggable?.onDrag,
                            onDragEnd = draggable?.onDragEnd,
                        )
                }
                element.children.forEachIndexed { index, child ->
                    lower(
                        child,
                        "$nodeId-$index",
                        layout,
                        ops,
                        hitRegions,
                        hoverRegions,
                        tooltipRegions,
                        frames,
                        descriptors,
                        focusNodes,
                        scrollRegions,
                        frameIndex,
                    )
                }
            }

            is UiElement.Row -> {
                element.children.forEachIndexed { index, child ->
                    lower(
                        child,
                        "$nodeId-$index",
                        layout,
                        ops,
                        hitRegions,
                        hoverRegions,
                        tooltipRegions,
                        frames,
                        descriptors,
                        focusNodes,
                        scrollRegions,
                        frameIndex,
                    )
                }
            }

            is UiElement.Column -> {
                element.children.forEachIndexed { index, child ->
                    lower(
                        child,
                        "$nodeId-$index",
                        layout,
                        ops,
                        hitRegions,
                        hoverRegions,
                        tooltipRegions,
                        frames,
                        descriptors,
                        focusNodes,
                        scrollRegions,
                        frameIndex,
                    )
                }
            }

            is UiElement.Text -> {
                ops +=
                    RenderOp.DrawText(
                        x = node.x,
                        y = node.y,
                        width = node.width,
                        value = element.text,
                        color = element.color,
                        alignment = element.modifier.findTextAlignment()?.alignment ?: TextAlignment.Start,
                    )
            }

            is UiElement.Canvas -> {
                ops += RenderOp.DrawCanvas(node.x, node.y, node.width, node.height, element.onDraw)
            }

            is UiElement.TerminalSurface -> {
                ops += RenderOp.DrawTerminalSurface(node.x, node.y, node.width, node.height, element.snapshot)
                // Auto-claim focus only if the element doesn't already carry an explicit
                // `Modifier.focusable(...)` (which would have been collected above).
                if (element.modifier.findFocusable() == null) {
                    focusNodes +=
                        FocusNode(
                            nodeId = nodeId,
                            frameIndex = frameIndex,
                            x = node.x,
                            y = node.y,
                            width = node.width,
                            height = node.height,
                            tabOrder = 0,
                            handler =
                                FocusHandler(
                                    onKeyPressed = { key, _ -> element.onKey(key) },
                                    onKeyReleased = element.onKeyReleased,
                                    onCharTyped = element.onCharTyped,
                                ),
                        )
                }
            }

            is UiElement.CodeEditor -> {
                lowerCodeEditor(
                    element,
                    nodeId,
                    node,
                    frameIndex,
                    ops,
                    hitRegions,
                    focusNodes,
                    scrollRegions,
                )
            }

            is UiElement.IfNode -> {
                val subOps = mutableListOf<RenderOp>()
                val subFrameIndex = frames.size
                frames += subOps
                descriptors += FrameDescriptor(origin = null, visible = element.condition)
                element.children.forEachIndexed { index, child ->
                    lower(
                        child,
                        "$nodeId-$index",
                        layout,
                        subOps,
                        hitRegions,
                        hoverRegions,
                        tooltipRegions,
                        frames,
                        descriptors,
                        focusNodes,
                        scrollRegions,
                        subFrameIndex,
                    )
                }
            }

            is UiElement.Overlay -> {
                error("unreachable: Overlay handled before the layout-node guard")
            }

            is UiElement.ScrollArea -> {
                lowerScrollArea(
                    element,
                    nodeId,
                    node,
                    ops,
                    hitRegions,
                    hoverRegions,
                    tooltipRegions,
                    frames,
                    descriptors,
                    focusNodes,
                    scrollRegions,
                    parentFrameIndex = frameIndex,
                )
            }
        }
    }

    private fun lowerOverlay(
        element: UiElement.Overlay,
        nodeId: String,
        parentLayout: Map<String, LayoutNode>,
        hitRegions: MutableList<HitRegion>,
        hoverRegions: MutableList<HoverRegion>,
        tooltipRegions: MutableList<TooltipRegion>,
        frames: MutableList<MutableList<RenderOp>>,
        descriptors: MutableList<FrameDescriptor>,
        focusNodes: MutableList<FocusNode>,
        scrollRegions: MutableList<ScrollRegion>,
    ) {
        val size = element.modifier.findSize()?.size
        val overlayWidth = size?.width ?: parentLayout["root"]?.width ?: 0
        val overlayHeight = size?.height ?: parentLayout["root"]?.height ?: 0

        val subLayout =
            UiLayoutResolver(overlayWidth, overlayHeight, fontMetrics)
                .resolve(element, rootNodeId = nodeId, rootX = 0, rootY = 0)

        val subOps = mutableListOf<RenderOp>()
        val subFrameIndex = frames.size
        frames += subOps
        descriptors += FrameDescriptor(origin = element.anchor, visible = element.visible)
        element.children.forEachIndexed { index, child ->
            lower(
                child,
                "$nodeId-$index",
                subLayout,
                subOps,
                hitRegions,
                hoverRegions,
                tooltipRegions,
                frames,
                descriptors,
                focusNodes,
                scrollRegions,
                subFrameIndex,
            )
        }
    }

    private data class FrameDescriptor(
        val origin: Value<Position>?,
        val visible: Value<Boolean>?,
    )

    private fun lowerScrollArea(
        element: UiElement.ScrollArea,
        nodeId: String,
        outer: LayoutNode,
        parentOps: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        hoverRegions: MutableList<HoverRegion>,
        tooltipRegions: MutableList<TooltipRegion>,
        frames: MutableList<MutableList<RenderOp>>,
        descriptors: MutableList<FrameDescriptor>,
        focusNodes: MutableList<FocusNode>,
        scrollRegions: MutableList<ScrollRegion>,
        parentFrameIndex: Int,
    ) {
        // Optional background fill on the viewport itself.
        element.modifier.findBackground()?.let { bg ->
            parentOps += RenderOp.FillRect(outer.x, outer.y, outer.width, outer.height, bg.color)
        }

        // Push the viewport clip in the *parent* frame so the sub-frame's
        // translated draw calls are masked to the visible rectangle.
        parentOps += RenderOp.PushClip(outer.x, outer.y, outer.width, outer.height)

        // Resolve children's static layout in their own coordinate space (origin 0,0).
        val subLayout =
            UiLayoutResolver(outer.width, outer.height, fontMetrics)
                .resolve(element, rootNodeId = nodeId, rootX = 0, rootY = 0)

        // Sub-frame whose dynamic origin is (outer.x - scrollX, outer.y - scrollY).
        val subOps = mutableListOf<RenderOp>()
        val subFrameIndex = frames.size
        frames += subOps
        val scrollX = element.scrollX
        val scrollY = element.scrollY
        val originX = outer.x
        val originY = outer.y
        val origin =
            ru.lazyhat.kraftui.foundation.value {
                Position(originX - scrollX.value, originY - scrollY.value)
            }
        descriptors += FrameDescriptor(origin = origin, visible = null)

        // Hit-region clip stamped onto every region collected inside the sub-tree.
        val hitClip =
            HitClip(
                frameIndex = parentFrameIndex,
                x = outer.x,
                y = outer.y,
                width = outer.width,
                height = outer.height,
            )

        val hitRegionsBefore = hitRegions.size
        element.children.forEachIndexed { index, child ->
            lower(
                child,
                "$nodeId-$index",
                subLayout,
                subOps,
                hitRegions,
                hoverRegions,
                tooltipRegions,
                frames,
                descriptors,
                focusNodes,
                scrollRegions,
                subFrameIndex,
            )
        }
        for (i in hitRegionsBefore until hitRegions.size) {
            hitRegions[i] = hitRegions[i].copy(clip = hitClip)
        }

        // Register the scroll handler for wheel events landing on the viewport.
        scrollRegions +=
            ScrollRegion(
                nodeId = nodeId,
                frameIndex = parentFrameIndex,
                x = outer.x,
                y = outer.y,
                width = outer.width,
                height = outer.height,
                onScroll = element.onScroll,
            )

        parentOps += RenderOp.PopClip
    }

    private fun lowerCodeEditor(
        element: UiElement.CodeEditor,
        nodeId: String,
        node: LayoutNode,
        frameIndex: Int,
        ops: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        focusNodes: MutableList<FocusNode>,
        scrollRegions: MutableList<ScrollRegion>,
    ) {
        ops +=
            RenderOp.DrawCodeEditor(
                x = node.x,
                y = node.y,
                width = node.width,
                height = node.height,
                viewModel = element.viewModel,
                fontWidth = element.fontWidth,
                fontHeight = element.fontHeight,
            )

        val viewModel = element.viewModel
        val fontWidth = element.fontWidth
        val fontHeight = element.fontHeight
        val width = node.width
        val height = node.height

        focusNodes +=
            FocusNode(
                nodeId = nodeId,
                frameIndex = frameIndex,
                x = node.x,
                y = node.y,
                width = width,
                height = height,
                tabOrder = element.modifier.findFocusable()?.tabOrder ?: 0,
                handler =
                    FocusHandler(
                        onKeyPressed = { key, mods ->
                            viewModel.value.onKeyPressed(key, modifiers = mods, visibleLines = height / fontHeight)
                        },
                        onCharTyped = { ch ->
                            viewModel.value.onCharTyped(ch, visibleLines = height / fontHeight)
                        },
                    ),
            )

        // Hit region: a click translates pixel coordinates to (line, column),
        // forwards to the view-model and lets the executor focus this node.
        // The hit-region's onClick receives no coordinates, so the runtime
        // executor calls the view-model directly via mouseClicked routing.
        // Until that routing is wired up, we forward a "best effort" click
        // pinning at (scrollLine, 0) so focusing alone works correctly.
        hitRegions +=
            HitRegion(
                nodeId = nodeId,
                frameIndex = frameIndex,
                x = node.x,
                y = node.y,
                width = width,
                height = height,
                zIndex = element.modifier.findZIndex()?.zIndex ?: 0,
                onClick = {
                    val vm = viewModel.value
                    vm.onMouseClickAt(vm.scrollLine, 0)
                },
                onClickAt = { localX, localY ->
                    val vm = viewModel.value
                    val gutter =
                        ru.lazyhat.kraftui.editor.CodeEditorMetrics
                            .gutterPixelWidth(
                                ru.lazyhat.kraftui.editor.CodeEditorMetrics
                                    .lineCount(vm.text),
                                fontWidth,
                            )
                    val line = vm.scrollLine + (localY / fontHeight)
                    val column = pixelToColumnAtLine(vm.text, line, (localX - gutter).coerceAtLeast(0), fontWidth)
                    vm.onMouseClickAt(line, column)
                },
            )

        scrollRegions +=
            ScrollRegion(
                nodeId = nodeId,
                frameIndex = frameIndex,
                x = node.x,
                y = node.y,
                width = width,
                height = height,
                onScroll = { delta ->
                    val lines =
                        when {
                            delta > 0.0 -> -1
                            delta < 0.0 -> 1
                            else -> 0
                        }
                    if (lines != 0) viewModel.value.onScroll(lines)
                    lines != 0
                },
            )
    }

    /**
     * Find the column on [line] of [text] whose rendered start-position is
     * closest to [localX] pixels from the line's left edge. Uses the
     * compiler's [fontMetrics] when available so variable-width MC fonts
     * map clicks accurately; falls back to a fixed-width division (using
     * [fontWidth]) if no metrics are available.
     */
    private fun pixelToColumnAtLine(
        text: String,
        line: Int,
        localX: Int,
        fontWidth: Int,
    ): Int {
        val lineText =
            text
                .split('\n')
                .getOrNull(line)
                .orEmpty()
        val metrics = fontMetrics ?: return localX / fontWidth.coerceAtLeast(1)
        if (lineText.isEmpty()) return 0
        // Walk character by character; pick the column where the click
        // sits past the *midpoint* of the glyph at column n-1.
        var prevWidth = 0
        for (col in 1..lineText.length) {
            val w = metrics.width(lineText.substring(0, col))
            val midpoint = (prevWidth + w) / 2
            if (localX < midpoint) return col - 1
            prevWidth = w
        }
        return lineText.length
    }
}
