package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchTerminalInteractionPolicyTest {
    @Test
    fun hidesFocusHintWhenPoweredOff() {
        assertFalse(WorkbenchTerminalInteractionPolicy.showFocusHint(poweredOn = false, focused = false))
        assertTrue(WorkbenchTerminalInteractionPolicy.showFocusHint(poweredOn = true, focused = false))
    }

    @Test
    fun blocksTerminalInputWhenPoweredOffOrNotInTerminalMode() {
        assertFalse(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, poweredOn = false, focused = true))
        assertFalse(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.EDITOR, poweredOn = true, focused = true))
        assertTrue(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, poweredOn = true, focused = true))
    }
}