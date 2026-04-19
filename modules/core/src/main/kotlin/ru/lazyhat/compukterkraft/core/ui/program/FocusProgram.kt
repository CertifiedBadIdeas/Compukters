package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiRole

data class FocusProgram(
    val targets: List<FocusTarget>,
)

data class FocusTarget(
    val regionId: String,
    val role: UiRole?,
    val order: Int,
)
