package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiRole

data class HitTestProgram(
    val regions: List<HitRegion>,
)

data class HitRegion(
    val regionId: String,
    val nodeId: String,
    val role: UiRole?,
    val zIndex: Int,
    val focusable: Boolean,
)
