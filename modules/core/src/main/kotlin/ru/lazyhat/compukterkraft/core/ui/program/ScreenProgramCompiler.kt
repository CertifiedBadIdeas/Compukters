package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.Value
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findBackground
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findClickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findFocusable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findHoverable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findSize
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findTooltip
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findZIndex

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
                            onKeyPressed = f.onKeyPressed,
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
                element.modifier.findClickable()?.let { clickable ->
                    hitRegions +=
                        HitRegion(
                            nodeId = nodeId,
                            frameIndex = frameIndex,
                            x = node.x,
                            y = node.y,
                            width = node.width,
                            height = node.height,
                            zIndex = element.modifier.findZIndex()?.zIndex ?: 0,
                            onClick = clickable.onClick,
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
                        frameIndex,
                    )
                }
            }

            is UiElement.Text -> {
                ops += RenderOp.DrawText(node.x, node.y, element.text, element.color)
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
                                    onKeyPressed = element.onKey,
                                    onKeyReleased = element.onKeyReleased,
                                    onCharTyped = element.onCharTyped,
                                ),
                        )
                }
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
                        subFrameIndex,
                    )
                }
            }

            is UiElement.Overlay -> {
                error("unreachable: Overlay handled before the layout-node guard")
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
                subFrameIndex,
            )
        }
    }

    private data class FrameDescriptor(
        val origin: Value<Position>?,
        val visible: Value<Boolean>?,
    )
}
