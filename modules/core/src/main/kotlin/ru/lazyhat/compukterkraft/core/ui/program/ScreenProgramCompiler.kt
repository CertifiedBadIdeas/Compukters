package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.BackgroundModifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.find
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findBackground
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findClickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findZIndex
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.zIndex

class ScreenProgramCompiler(
    private val fontMetrics: FontMetrics? = null,
) {
    fun compile(root: UiElement): ScreenProgram {
        val resolvedLayout = UiLayoutResolver(rootWidth = 0, rootHeight = 0, fontMetrics = fontMetrics).resolve(root)
        val layoutNodes = resolvedLayout.values.toMutableList()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()

        lower(
            element = root,
            nodeId = "root",
            resolvedLayout = resolvedLayout,
            renderOps = renderOps,
            hitRegions = hitRegions,
            inputRoutes = inputRoutes,
            dynamicLayouts = dynamicLayouts,
            dynamicRenders = dynamicRenders,
        )

        return ScreenProgram(
            layoutProgram = LayoutProgram(layoutNodes, dynamicLayouts),
            renderProgram = RenderProgram(renderOps, dynamicRenders),
            hitTestProgram = HitTestProgram(hitRegions.sortedByDescending { it.zIndex }),
            inputProgram = InputProgram(inputRoutes),
        )
    }

    private fun lower(
        element: UiElement,
        nodeId: String,
        resolvedLayout: Map<String, LayoutNode>,
        renderOps: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
        dynamicLayouts: MutableList<DynamicLayoutFragment>,
        dynamicRenders: MutableList<DynamicRenderFragment>,
    ) {
        val layoutNode = resolvedLayout.getValue(nodeId)

        when (element) {
            is UiElement.Box -> {
                val backgroundColor = element.modifier.findBackground()?.color

                if (backgroundColor != null) {
                    renderOps += RenderOp.FillRect(nodeId, backgroundColor)
                }

                addInteraction(nodeId, element, hitRegions, inputRoutes)

                element.children.forEachIndexed { index, child ->
                    lower(
                        element = child,
                        nodeId = "$nodeId-$index",
                        resolvedLayout = resolvedLayout,
                        renderOps = renderOps,
                        hitRegions = hitRegions,
                        inputRoutes = inputRoutes,
                        dynamicLayouts = dynamicLayouts,
                        dynamicRenders = dynamicRenders,
                    )
                }
            }

            is UiElement.Row -> {
                element.children.forEachIndexed { index, child ->
                    lower(
                        element = child,
                        nodeId = "$nodeId-$index",
                        resolvedLayout = resolvedLayout,
                        renderOps = renderOps,
                        hitRegions = hitRegions,
                        inputRoutes = inputRoutes,
                        dynamicLayouts = dynamicLayouts,
                        dynamicRenders = dynamicRenders,
                    )
                }
            }

            is UiElement.Column -> {
                element.children.forEachIndexed { index, child ->
                    lower(
                        element = child,
                        nodeId = "$nodeId-$index",
                        resolvedLayout = resolvedLayout,
                        renderOps = renderOps,
                        hitRegions = hitRegions,
                        inputRoutes = inputRoutes,
                        dynamicLayouts = dynamicLayouts,
                        dynamicRenders = dynamicRenders,
                    )
                }
            }

            is UiElement.Text -> {
                renderOps += RenderOp.DrawText(nodeId, element.value.evaluate(), element.color)
            }

            is UiElement.TerminalSurface -> {
                renderOps += RenderOp.DrawTerminalSurface(nodeId, element.snapshot.evaluate())
                val regionId = "$nodeId-region"
                hitRegions += HitRegion(regionId, nodeId, element.modifier.findZIndex()?.zIndex ?: 0)
                inputRoutes += InputRoute(regionId, InputEventType.KeyPressed, "$regionId-key")
            }

            is UiElement.IfNode -> {
                dynamicLayouts += DynamicLayoutFragment { emptyList() }
                dynamicRenders += DynamicRenderFragment { emptyList() }
                if (element.condition.evaluate()) {
                    element.children.forEachIndexed { index, child ->
                        lower(
                            element = child,
                            nodeId = "$nodeId-if-$index",
                            resolvedLayout = resolvedLayout,
                            renderOps = renderOps,
                            hitRegions = hitRegions,
                            inputRoutes = inputRoutes,
                            dynamicLayouts = dynamicLayouts,
                            dynamicRenders = dynamicRenders,
                        )
                    }
                }
            }
        }
    }

    private fun addInteraction(
        nodeId: String,
        element: UiElement,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
    ) {
        if (element.modifier.findClickable() == null) {
            return
        }

        val regionId = "$nodeId-region"
        hitRegions += HitRegion(regionId, nodeId, element.modifier.findZIndex()?.zIndex ?: 0)
        inputRoutes += InputRoute(regionId, InputEventType.Click, "$regionId-click")
    }
}
