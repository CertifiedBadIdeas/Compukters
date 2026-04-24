package ru.lazyhat.compukterkraft.core.ui.program

data class ScreenProgram(
    val layoutProgram: LayoutProgram,
    val renderProgram: RenderProgram,
    val hitTestProgram: HitTestProgram,
    val inputProgram: InputProgram,
)
