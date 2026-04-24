package ru.lazyhat.compukterkraft.core.ui.program

data class FocusProgram(
    val targets: List<FocusTarget>,
)

data class FocusTarget(
    val regionId: String,
    val order: Int,
)
