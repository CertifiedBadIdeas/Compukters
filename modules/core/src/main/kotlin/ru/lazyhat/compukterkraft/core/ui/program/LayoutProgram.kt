package ru.lazyhat.compukterkraft.core.ui.program

data class LayoutProgram(
    val staticNodes: List<LayoutNode>,
    val dynamicFragments: List<DynamicLayoutFragment>,
)

data class LayoutNode(
    val nodeId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

fun interface DynamicLayoutFragment {
    fun evaluate(): List<LayoutNode>
}
