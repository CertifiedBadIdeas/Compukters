package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class ScreenRuntimeExecutorTest {
    @Test
    fun mouseClickDispatchesTopmostClickableRegion() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val program = compiler.compile(
            ui {
                button(text = textExpr { "Behind" }, modifier = Modifier.offset(4, 4).size(20, 20).zIndex(0)) { events += "behind" }
                button(text = textExpr { "Front" }, modifier = Modifier.offset(4, 4).size(20, 20).zIndex(1)) { events += "front" }
            },
        )

        val executor = ScreenRuntimeExecutor(
            program = program,
            slotProvider = { SlotValues() },
            clickHandlers = mapOf(
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
        var focused = false
        val compiler = ScreenProgramCompiler()
        val program = compiler.compile(
            ui {
                terminalSurface(
                    snapshot = expr { "snapshot" },
                    modifier = Modifier.offset(8, 8).size(80, 32).focusable(),
                    onKey = { keyCode -> keyCode == 257 },
                )
            },
        )

        val executor = ScreenRuntimeExecutor(
            program = program,
            slotProvider = { SlotValues() },
            clickHandlers = emptyMap(),
            keyHandlers = mapOf("root-0-region-key" to { keyCode: Int -> keyCode == 257 }),
            focusHandlers = mapOf("root-0-region" to { focused = true }),
        )

        assertTrue(executor.mouseClicked(10, 10))
        assertTrue(focused)
        assertTrue(executor.keyPressed(257))
    }

    @Test
    fun mouseClickTargetsOnlyRegionUnderCursor() {
        val events = mutableListOf<String>()
        var focused = false
        val compiler = ScreenProgramCompiler()
        val program = compiler.compile(
            ui {
                button(text = textExpr { "Power" }, modifier = Modifier.offset(4, 4).size(20, 20)) { events += "power" }
                terminalSurface(
                    snapshot = expr { "snapshot" },
                    modifier = Modifier.offset(40, 40).size(80, 32).focusable(),
                    onKey = { keyCode -> keyCode == 257 },
                )
            },
        )

        val executor = ScreenRuntimeExecutor(
            program = program,
            slotProvider = { SlotValues() },
            clickHandlers = mapOf("root-0-region-click" to { events += "power" }),
            keyHandlers = mapOf("root-1-region-key" to { keyCode: Int -> keyCode == 257 }),
            focusHandlers = mapOf("root-1-region" to { focused = true }),
        )

        assertTrue(executor.mouseClicked(50, 50))
        assertEquals(emptyList(), events)
        assertTrue(focused)
        assertTrue(executor.keyPressed(257))
    }
}