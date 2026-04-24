package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
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
        val program =
            ScreenProgramCompiler().compile(
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

        val executor = ScreenRuntimeExecutor(program)

        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun focusedTerminalReceivesKeyEventsRegardlessOfMousePosition() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(8, 8).size(80, 32),
                        onKey = { keyCode -> keyCode == 257 },
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        assertTrue(executor.keyPressed(257))
        assertFalse(executor.keyPressed(258))
    }

    @Test
    fun keyPressedReturnsFalseWhenNoFocusableElementExists() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button({}) { text(text = expr { "Noop" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        assertFalse(executor.keyPressed(257))
    }

    @Test
    fun mouseClickIgnoresAreasOutsideAnyRegion() {
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20),
                        onClick = { events += "power" },
                    ) { text(text = expr { "Power" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        assertFalse(executor.mouseClicked(100, 100))
        assertTrue(events.isEmpty())
    }

    @Test
    fun hiddenIfFrameDoesNotDispatchClicksToRegionsItOwns() {
        var shown = false
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui(Modifier.size(100, 100)) {
                    If(expr { shown }) {
                        button(
                            modifier = Modifier.offset(4, 4).size(20, 20),
                            onClick = { events += "hit" },
                        ) { text(text = expr { "Hidden" }) }
                    }
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        assertFalse(executor.mouseClicked(8, 8))
        assertTrue(events.isEmpty())

        shown = true
        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("hit"), events)
    }

    @Test
    fun overlayOriginTranslatesClickCoordinates() {
        var anchor = Position(50, 30)
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui(Modifier.size(200, 200)) {
                    overlay(
                        modifier = Modifier.size(20, 20),
                        anchor = expr { anchor },
                    ) {
                        button(
                            modifier = Modifier.size(20, 20).background(Color.Red),
                            onClick = { events += "popup" },
                        ) { text(text = expr { "X" }) }
                    }
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        assertFalse(executor.mouseClicked(0, 0))
        assertTrue(executor.mouseClicked(55, 35))
        assertEquals(listOf("popup"), events)

        // Move the overlay; the same screen position now misses while the new
        // anchor position hits.
        anchor = Position(100, 100)
        assertFalse(executor.mouseClicked(55, 35))
        assertTrue(executor.mouseClicked(105, 105))
    }

    @Test
    fun overlayVisibilityGatesRendering() {
        var shown = true
        val program =
            ScreenProgramCompiler().compile(
                ui(Modifier.size(100, 100)) {
                    overlay(
                        modifier = Modifier.size(20, 20),
                        anchor = expr { Position(0, 0) },
                        visible = expr { shown },
                    ) {
                        box(modifier = Modifier.size(20, 20).background(Color.Red))
                    }
                },
            )

        val backend = RecordingBackend()
        ScreenRuntimeExecutor(program).render(backend)
        assertEquals(1, backend.fillRects.size)

        shown = false
        val backend2 = RecordingBackend()
        ScreenRuntimeExecutor(program).render(backend2)
        assertEquals(0, backend2.fillRects.size)
    }

    private class RecordingBackend : RenderBackend {
        val fillRects = mutableListOf<IntArray>()

        override fun fillRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Color,
        ) {
            fillRects += intArrayOf(x, y, width, height)
        }

        override fun drawText(
            x: Int,
            y: Int,
            text: String,
            color: Color,
        ) {
        }

        override fun drawTerminalSurface(
            x: Int,
            y: Int,
            snapshot: Any?,
        ) {
        }
    }
}
