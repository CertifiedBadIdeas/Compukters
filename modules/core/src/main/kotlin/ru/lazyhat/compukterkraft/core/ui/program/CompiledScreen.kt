package ru.lazyhat.compukterkraft.core.ui.program

/**
 * Result of compiling a [ru.lazyhat.compukterkraft.core.ui.foundation.UiElement] tree.
 *
 * Handlers (lambdas) are kept outside [ScreenProgram] so the program itself remains
 * a pure data structure that can be structurally compared, cached, and serialized later.
 */
data class CompiledScreen(
    val program: ScreenProgram,
    val clickHandlers: Map<String, () -> Unit>,
    val keyHandler: ((Int) -> Boolean)?,
)
