package ru.lazyhat.compukterkraft.core.ui.program

/**
 * Runtime-side counterpart of [CompiledScreen].
 *
 * Responsibilities: render via [RenderBackend]; route mouse clicks through the
 * [HitTestProgram]; forward key events to the single focused element (if any).
 */
class ScreenRuntimeExecutor(
    private val compiled: CompiledScreen,
) {
    private val program: ScreenProgram get() = compiled.program

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

    fun mouseClicked(
        x: Int,
        y: Int,
    ): Boolean {
        val hitRegion =
            program.hitTestProgram.regions.firstOrNull { region ->
                val bounds = boundsFor(region.nodeId)
                x >= bounds.x && y >= bounds.y && x < bounds.x + bounds.width && y < bounds.y + bounds.height
            } ?: return false

        val route =
            program.inputProgram.routes.firstOrNull {
                it.regionId == hitRegion.regionId && it.eventType == InputEventType.Click
            } ?: return false

        val handler = compiled.clickHandlers[route.handlerId] ?: return false
        handler.invoke()
        return true
    }

    fun keyPressed(keyCode: Int): Boolean = compiled.keyHandler?.invoke(keyCode) ?: false

    private fun boundsFor(nodeId: String): LayoutNode =
        program.layoutProgram.staticNodes.firstOrNull { it.nodeId == nodeId }
            ?: error("Missing layout bounds for node '$nodeId'")
}
