package ru.lazyhat.compukterkraft.core.ui.program

data class RenderProgram(
    val staticOps: List<RenderOp>,
    val dynamicFragments: List<DynamicRenderFragment>,
)

sealed interface RenderOp {
    data class FillRect(
        val nodeId: String,
        val color: Int,
    ) : RenderOp

    data class DrawText(
        val nodeId: String,
        val value: String,
        val color: Int,
    ) : RenderOp

    data class DrawTerminalSurface(
        val nodeId: String,
        val snapshot: Any?,
    ) : RenderOp
}

fun interface DynamicRenderFragment {
    fun evaluate(): List<RenderOp>
}
