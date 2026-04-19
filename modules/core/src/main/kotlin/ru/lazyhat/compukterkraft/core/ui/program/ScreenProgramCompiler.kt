package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement

class ScreenProgramCompiler {
    fun compile(root: UiElement): ScreenProgram {
        val layoutNodes = mutableListOf<LayoutNode>()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val focusTargets = mutableListOf<FocusTarget>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()

        lower(
            element = root,
            nodeId = "root",
            parentX = 0,
            parentY = 0,
            layoutNodes = layoutNodes,
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
        parentX: Int,
        parentY: Int,
        layoutNodes: MutableList<LayoutNode>,
        renderOps: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
        focusTargets: MutableList<FocusTarget>,
        dynamicLayouts: MutableList<DynamicLayoutFragment>,
        dynamicRenders: MutableList<DynamicRenderFragment>,
    ) {
        val x = parentX + element.modifier.x
        val y = parentY + element.modifier.y
        val width = element.modifier.width ?: 0
        val height = element.modifier.height ?: 0

        when (element) {
            is UiElement.Box -> {
                layoutNodes += LayoutNode(nodeId, x, y, width, height)
                if (element.modifier.role == ru.lazyhat.compukterkraft.core.ui.foundation.UiRole.Button) {
                    renderOps += RenderOp.FillRect(nodeId, 0xFF1D2330.toInt())
                }
                addInteraction(nodeId, element, hitRegions, inputRoutes, focusTargets)
                element.children.forEachIndexed { index, child ->
                    lower(
                        element = child,
                        nodeId = "$nodeId-$index",
                        parentX = x,
                        parentY = y,
                        layoutNodes = layoutNodes,
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
                layoutNodes += LayoutNode(nodeId, x, y, element.modifier.width ?: 80, element.modifier.height ?: 9)
                renderOps += RenderOp.DrawText(nodeId, element.value.evaluate(), element.color)
            }

            is UiElement.TerminalSurface -> {
                layoutNodes += LayoutNode(nodeId, x, y, width, height)
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
                            parentX = x,
                            parentY = y,
                            layoutNodes = layoutNodes,
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
