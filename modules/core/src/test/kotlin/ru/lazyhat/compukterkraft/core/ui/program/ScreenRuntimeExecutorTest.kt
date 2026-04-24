package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.zIndex
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenRuntimeExecutorTest {
    @Test
    fun mouseClickDispatchesTopmostClickableRegion() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val program =
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

        val executor =
            ScreenRuntimeExecutor(
                program = program,
                slotProvider = { SlotValues() },
                clickHandlers =
                    mapOf(
                        "root-0-region-click" to { events += "behind" },
                        "root-1-region-click" to { events += "front" },
                    ),
                keyHandlers = emptyMap(),
            )

        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun focusedTerminalReceivesKeyEventsThroughInputProgram() {
        val compiler = ScreenProgramCompiler()
        val program =
            compiler.compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(8, 8).size(80, 32),
                        onKey = { keyCode -> keyCode == 257 },
                    )
                },
            )

        val executor =
            ScreenRuntimeExecutor(
                program = program,
                slotProvider = { SlotValues() },
                clickHandlers = emptyMap(),
                keyHandlers = mapOf("root-0-region-key" to { keyCode: Int -> keyCode == 257 }),
            )

        assertTrue(executor.mouseClicked(10, 10))
        assertTrue(executor.keyPressed(257))
    }

    @Test
    fun mouseClickTargetsOnlyRegionUnderCursor() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val program =
            compiler.compile(
                ui {
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20),
                        onClick = { events += "power" },
                    ) { text(text = expr { "Power" }) }

                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(40, 40).size(80, 32),
                        onKey = { keyCode -> keyCode == 257 },
                    )
                },
            )

        val executor =
            ScreenRuntimeExecutor(
                program = program,
                slotProvider = { SlotValues() },
                clickHandlers = mapOf("root-0-region-click" to { events += "power" }),
                keyHandlers = mapOf("root-1-region-key" to { keyCode: Int -> keyCode == 257 }),
            )

        assertTrue(executor.keyPressed(257))
    }
}
