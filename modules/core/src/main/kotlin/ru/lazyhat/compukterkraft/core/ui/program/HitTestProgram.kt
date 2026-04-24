package ru.lazyhat.compukterkraft.core.ui.program

data class HitTestProgram(
    val regions: List<HitRegion>,
)

data class HitRegion(
    val regionId: String,
    val nodeId: String,
    val zIndex: Int,
)
