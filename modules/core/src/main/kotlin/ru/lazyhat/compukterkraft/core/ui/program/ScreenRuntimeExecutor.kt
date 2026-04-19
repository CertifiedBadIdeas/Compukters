package ru.lazyhat.compukterkraft.core.ui.program

class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
    private val slotProvider: () -> SlotValues,
    private val clickHandlers: Map<String, () -> Unit>,
    private val keyHandlers: Map<String, (Int) -> Boolean>,
    private val focusHandlers: Map<String, () -> Unit> = emptyMap(),
) {
    private var focusedRegionId: String? = null

    fun render(backend: RenderBackend) {
        program.renderProgram.staticOps.forEach { op ->
            when (op) {
                is RenderOp.FillRect -> {
                    val bounds = boundsFor(op.nodeId)
                    backend.fillRect(bounds.x, bounds.y, bounds.width, bounds.height, op.color)
                }

                is RenderOp.DrawText -> {
                    val bounds = boundsFor(op.nodeId)
                    backend.drawText(bounds.x, bounds.y, op.value, op.color)
                }

                is RenderOp.DrawTerminalSurface -> {
                    val bounds = boundsFor(op.nodeId)
                    backend.drawTerminalSurface(bounds.x, bounds.y, op.snapshot)
                }
            }
        }
    }

    fun mouseClicked(x: Int, y: Int): Boolean {
        val hitRegion = program.hitTestProgram.regions.firstOrNull() ?: return false
        var consumed = false

        if (hitRegion.focusable) {
            focusedRegionId = hitRegion.regionId
            focusHandlers[hitRegion.regionId]?.invoke()
            consumed = true
        }

        val route = program.inputProgram.routes.firstOrNull {
            it.regionId == hitRegion.regionId && it.eventType == InputEventType.Click
        }

        if (route == null) {
            return consumed
        }

        clickHandlers[route.handlerId]?.invoke()
        return true
    }

    fun keyPressed(keyCode: Int): Boolean {
        val regionId = focusedRegionId ?: return false
        val route = program.inputProgram.routes.firstOrNull {
            it.regionId == regionId && it.eventType == InputEventType.KeyPressed
        } ?: return false

        return keyHandlers[route.handlerId]?.invoke(keyCode) ?: false
    }

    private fun boundsFor(nodeId: String): LayoutNode =
        program.layoutProgram.staticNodes.firstOrNull { it.nodeId == nodeId }
            ?: error("Missing layout bounds for node '$nodeId'")
}
