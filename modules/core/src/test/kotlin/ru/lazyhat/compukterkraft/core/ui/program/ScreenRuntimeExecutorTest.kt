package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.zIndex
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenRuntimeExecutorTest {
    @Test
    fun mouseClickDispatchesTopmostClickableRegion() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val compiled =
            compiler.compile(
                ui {
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20).zIndex(0),
                        onClick = { events += "behind" },
                    ) { text(text = expr { "Behind" }) }
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20).zIndex(1),
                        onClick = { events += "front" },
                    ) { text(text = expr { "Front" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(compiled)

        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun focusedTerminalReceivesKeyEventsRegardlessOfMousePosition() {
        val compiler = ScreenProgramCompiler()
        val compiled =
            compiler.compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(8, 8).size(80, 32),
                        onKey = { keyCode -> keyCode == 257 },
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(compiled)

        assertTrue(executor.keyPressed(257))
        assertFalse(executor.keyPressed(258))
    }

    @Test
    fun keyPressedReturnsFalseWhenNoFocusableElementExists() {
        val compiler = ScreenProgramCompiler()
        val compiled =
            compiler.compile(
                ui {
                    button({}) { text(text = expr { "Noop" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(compiled)

        assertFalse(executor.keyPressed(257))
    }

    @Test
    fun mouseClickIgnoresAreasOutsideAnyRegion() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val compiled =
            compiler.compile(
                ui {
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20),
                        onClick = { events += "power" },
                    ) { text(text = expr { "Power" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(compiled)

        assertFalse(executor.mouseClicked(100, 100))
        assertTrue(events.isEmpty())
    }
}
