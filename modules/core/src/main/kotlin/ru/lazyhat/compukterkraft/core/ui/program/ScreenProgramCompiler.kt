package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.ValueExpression
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findBackground
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findClickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findSize
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
        val focus = FocusAccumulator()

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
            frames = frames,
            descriptors = descriptors,
            focus = focus,
            frameIndex = 0,
        )

        return ScreenProgram(
            frames =
                frames.mapIndexed { index, ops ->
                    val descriptor = descriptors[index]
                    RenderFrame(origin = descriptor.origin, visible = descriptor.visible, ops = ops.toList())
                },
            hitRegions = hitRegions.sortedByDescending { it.zIndex },
            focusedNodeId = focus.nodeId,
            focusRegion = focus.region,
            keyHandler = focus.handler,
        )
    }

    private fun lower(
        element: UiElement,
        nodeId: String,
        layout: Map<String, LayoutNode>,
        ops: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        frames: MutableList<MutableList<RenderOp>>,
        descriptors: MutableList<FrameDescriptor>,
        focus: FocusAccumulator,
        frameIndex: Int,
    ) {
        if (element is UiElement.Overlay) {
            lowerOverlay(element, nodeId, layout, hitRegions, frames, descriptors, focus)
            return
        }

        val node = layout[nodeId] ?: return
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
                    lower(child, "$nodeId-$index", layout, ops, hitRegions, frames, descriptors, focus, frameIndex)
                }
            }

            is UiElement.Row -> {
                element.children.forEachIndexed { index, child ->
                    lower(child, "$nodeId-$index", layout, ops, hitRegions, frames, descriptors, focus, frameIndex)
                }
            }

            is UiElement.Column -> {
                element.children.forEachIndexed { index, child ->
                    lower(child, "$nodeId-$index", layout, ops, hitRegions, frames, descriptors, focus, frameIndex)
                }
            }

            is UiElement.Text -> {
                ops += RenderOp.DrawText(node.x, node.y, element.value, element.color)
            }

            is UiElement.Canvas -> {
                ops += RenderOp.DrawCanvas(node.x, node.y, node.width, node.height, element.onDraw)
            }

            is UiElement.TerminalSurface -> {
                ops += RenderOp.DrawTerminalSurface(node.x, node.y, node.width, node.height, element.snapshot)
                focus.claim(
                    nodeId = nodeId,
                    region =
                        FocusRegion(
                            nodeId = nodeId,
                            frameIndex = frameIndex,
                            x = node.x,
                            y = node.y,
                            width = node.width,
                            height = node.height,
                        ),
                    handler =
                        FocusHandler(
                            onKeyPressed = element.onKey,
                            onKeyReleased = element.onKeyReleased,
                            onCharTyped = element.onCharTyped,
                        ),
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
                        frames,
                        descriptors,
                        focus,
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
        frames: MutableList<MutableList<RenderOp>>,
        descriptors: MutableList<FrameDescriptor>,
        focus: FocusAccumulator,
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
                frames,
                descriptors,
                focus,
                subFrameIndex,
            )
        }
    }

    private data class FrameDescriptor(
        val origin: ValueExpression<Position>?,
        val visible: ValueExpression<Boolean>?,
    )

    private class FocusAccumulator {
        var nodeId: String? = null
            private set
        var region: FocusRegion? = null
            private set
        var handler: FocusHandler? = null
            private set

        fun claim(
            nodeId: String,
            region: FocusRegion,
            handler: FocusHandler,
        ) {
            check(this.nodeId == null) {
                "UI DSL: multiple focusable elements are not supported " +
                    "(already focused: '${this.nodeId}', new: '$nodeId')"
            }
            this.nodeId = nodeId
            this.region = region
            this.handler = handler
        }
    }
}
