package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement

class ScreenProgramCompiler {
    fun compile(root: UiElement): ScreenProgram {
        val resolvedLayout = UiLayoutResolver(rootWidth = 0, rootHeight = 0).resolve(root)
        val layoutNodes = resolvedLayout.values.toMutableList()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val focusTargets = mutableListOf<FocusTarget>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()

        lower(
            element = root,
            nodeId = "root",
            resolvedLayout = resolvedLayout,
            renderOps = renderOps,
            hitRegions = hitRegions,
            inputRoutes = inputRoutes,
            focusTargets = focusTargets,
            dynamicLayouts = dynamicLayouts,
            dynamicRenders = dynamicRenders,
        )

        return ScreenProgram(
            layoutProgram = LayoutProgram(layoutNodes, dynamicLayouts),
            renderProgram = RenderProgram(renderOps, dynamicRenders),
            hitTestProgram = HitTestProgram(hitRegions.sortedByDescending { it.zIndex }),
            inputProgram = InputProgram(inputRoutes),
            focusProgram = FocusProgram(focusTargets),
        )
    }

    private fun lower(
        element: UiElement,
        nodeId: String,
        resolvedLayout: Map<String, LayoutNode>,
        renderOps: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
        focusTargets: MutableList<FocusTarget>,
        dynamicLayouts: MutableList<DynamicLayoutFragment>,
        dynamicRenders: MutableList<DynamicRenderFragment>,
    ) {
        val layoutNode = resolvedLayout.getValue(nodeId)

        when (element) {
            is UiElement.Box -> {
                if (element.modifier.role == ru.lazyhat.compukterkraft.core.ui.foundation.UiRole.Button) {
                    renderOps += RenderOp.FillRect(nodeId, element.modifier.color ?: Color.Transparent)
                }
                addInteraction(nodeId, element, hitRegions, inputRoutes, focusTargets)
                element.children.forEachIndexed { index, child ->
                    lower(
                        element = child,
                        nodeId = "$nodeId-$index",
                        resolvedLayout = resolvedLayout,
                        renderOps = renderOps,
                        hitRegions = hitRegions,
                        inputRoutes = inputRoutes,
                        focusTargets = focusTargets,
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
                        focusTargets = focusTargets,
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
                        focusTargets = focusTargets,
                        dynamicLayouts = dynamicLayouts,
                        dynamicRenders = dynamicRenders,
                    )
                }
            }

            is UiElement.Text -> {
                renderOps += RenderOp.DrawText(nodeId, element.value.evaluate(), element.modifier.color ?: Color.Transparent)
            }

            is UiElement.TerminalSurface -> {
                renderOps += RenderOp.DrawTerminalSurface(nodeId, element.snapshot.evaluate())
                val regionId = "$nodeId-region"
                hitRegions += HitRegion(regionId, nodeId, element.modifier.role, element.modifier.zIndex, element.modifier.focusable)
                inputRoutes += InputRoute(regionId, InputEventType.KeyPressed, "$regionId-key")
                if (element.modifier.focusable) {
                    focusTargets += FocusTarget(regionId, element.modifier.role, focusTargets.size)
                }
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
                            focusTargets = focusTargets,
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
        focusTargets: MutableList<FocusTarget>,
    ) {
        if (element.modifier.onClick == null) {
            return
        }

        val regionId = "$nodeId-region"
        hitRegions += HitRegion(regionId, nodeId, element.modifier.role, element.modifier.zIndex, element.modifier.focusable)
        inputRoutes += InputRoute(regionId, InputEventType.Click, "$regionId-click")
        if (element.modifier.focusable) {
            focusTargets += FocusTarget(regionId, element.modifier.role, focusTargets.size)
        }
    }
}
