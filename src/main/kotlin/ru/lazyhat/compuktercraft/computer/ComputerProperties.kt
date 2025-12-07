package ru.lazyhat.compuktercraft.computer

import ru.lazyhat.compuktercraft.block.ComputerFamily

data class ComputerProperties(
    val family: ComputerFamily,
    val label: String?,
)
