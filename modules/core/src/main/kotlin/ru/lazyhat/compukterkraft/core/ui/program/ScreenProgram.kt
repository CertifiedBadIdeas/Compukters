package ru.lazyhat.compukterkraft.core.ui.program

data class ScreenProgram(
    val layoutProgram: LayoutProgram,
    val renderProgram: RenderProgram,
    val hitTestProgram: HitTestProgram,
    val inputProgram: InputProgram,
    /**
     * Node id of the element that owns keyboard input for this program, or `null`
     * if no element is focusable.
     *
     * Minimal focus model: there is at most one focusable element per screen.
     * If present, it is always focused for the lifetime of the program. The compiler
     * rejects screens with more than one focusable element.
     */
    val focusedNodeId: String? = null,
)
