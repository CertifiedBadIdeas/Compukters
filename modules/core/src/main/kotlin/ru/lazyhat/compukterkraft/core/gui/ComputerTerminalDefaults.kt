package ru.lazyhat.compukterkraft.core.gui

import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

object ComputerTerminalDefaults {
    fun fallbackSnapshot(family: ComputerFamily): ScreenBufferSnapshot =
        ScreenBufferSnapshot.empty(
            width = Config.DEFAULT_COMPUTER_TERM_WIDTH,
            height = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
            colour = family != ComputerFamily.NORMAL,
        )
}