package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchMode

object WorkbenchTerminalInteractionPolicy {
    fun showFocusHint(
        poweredOn: Boolean,
        focused: Boolean,
    ): Boolean = poweredOn && !focused

    fun canAcceptInput(
        mode: WorkbenchMode,
        poweredOn: Boolean,
        focused: Boolean,
    ): Boolean = mode == WorkbenchMode.TERMINAL && poweredOn && focused
}