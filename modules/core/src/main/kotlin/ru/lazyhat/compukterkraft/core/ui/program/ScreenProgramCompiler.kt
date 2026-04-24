package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findBackground
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findClickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.findZIndex

class ScreenProgramCompiler(
    private val fontMetrics: FontMetrics? = null,
) {
    fun compile(root: UiElement): CompiledScreen {
        val resolvedLayout = UiLayoutResolver(rootWidth = 0, rootHeight = 0, fontMetrics = fontMetrics).resolve(root)
        val layoutNodes = resolvedLayout.values.toMutableList()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()
        val clickHandlers = mutableMapOf<String, () -> Unit>()
        val focus = FocusAccumulator()

        lower(
            element = root,
            nodeId = "root",
            resolvedLayout = resolvedLayout,
            renderOps = renderOps,
            hitRegions = hitRegions,
            inputRoutes = inputRoutes,
            dynamicLayouts = dynamicLayouts,
            dynamicRenders = dynamicRenders,
            clickHandlers = clickHandlers,
            focus = focus,
        )

        val program =
            ScreenProgram(
                layoutProgram = LayoutProgram(layoutNodes, dynamicLayouts),
                renderProgram = RenderProgram(renderOps, dynamicRenders),
                hitTestProgram = HitTestProgram(hitRegions.sortedByDescending { it.zIndex }),
                inputProgram = InputProgram(inputRoutes),
                focusedNodeId = focus.nodeId,
            )

        return CompiledScreen(
            program = program,
            clickHandlers = clickHandlers.toMap(),
            keyHandler = focus.keyHandler,
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
        clickHandlers: MutableMap<String, () -> Unit>,
        focus: FocusAccumulator,
    ) {
        when (element) {
            is UiElement.Box -> {
                val backgroundColor = element.modifier.findBackground()?.color

                if (backgroundColor != null) {
                    renderOps += RenderOp.FillRect(nodeId, backgroundColor)
                }

                addClickInteraction(nodeId, element, hitRegions, inputRoutes, clickHandlers)

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
                        clickHandlers = clickHandlers,
                        focus = focus,
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
                        clickHandlers = clickHandlers,
                        focus = focus,
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
                        clickHandlers = clickHandlers,
                        focus = focus,
                    )
                }
            }

            is UiElement.Text -> {
                renderOps += RenderOp.DrawText(nodeId, element.value.evaluate(), element.color)
            }

            is UiElement.TerminalSurface -> {
                renderOps += RenderOp.DrawTerminalSurface(nodeId, element.snapshot.evaluate())
                focus.claim(nodeId, element.onKey)
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
                            clickHandlers = clickHandlers,
                            focus = focus,
                        )
                    }
                }
            }
        }
    }

    private fun addClickInteraction(
        nodeId: String,
        element: UiElement,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
        clickHandlers: MutableMap<String, () -> Unit>,
    ) {
        val clickable = element.modifier.findClickable() ?: return

        val regionId = "$nodeId-region"
        val handlerId = "$regionId-click"
        hitRegions += HitRegion(regionId, nodeId, element.modifier.findZIndex()?.zIndex ?: 0)
        inputRoutes += InputRoute(regionId, InputEventType.Click, handlerId)
        clickHandlers[handlerId] = clickable.onClick
    }

    private class FocusAccumulator {
        var nodeId: String? = null
            private set
        var keyHandler: ((Int) -> Boolean)? = null
            private set

        fun claim(
            nodeId: String,
            onKey: (Int) -> Boolean,
        ) {
            check(this.nodeId == null) {
                "UI DSL: multiple focusable elements are not supported (already focused: '${this.nodeId}', new: '$nodeId')"
            }
            this.nodeId = nodeId
            this.keyHandler = onKey
        }
    }
}
