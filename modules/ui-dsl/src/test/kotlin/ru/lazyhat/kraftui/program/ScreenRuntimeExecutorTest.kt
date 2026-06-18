package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.Position
import ru.lazyhat.kraftui.foundation.modifier.TextAlignment
import ru.lazyhat.kraftui.foundation.modifier.background
import ru.lazyhat.kraftui.foundation.modifier.draggable
import ru.lazyhat.kraftui.foundation.modifier.focusable
import ru.lazyhat.kraftui.foundation.modifier.offset
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.modifier.textAlign
import ru.lazyhat.kraftui.foundation.modifier.width
import ru.lazyhat.kraftui.foundation.modifier.zIndex
import ru.lazyhat.kraftui.foundation.tickValue
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenRuntimeExecutorTest {
    private fun emptySnapshot(): Any = "terminal"

    @Test
    fun mouseClickDispatchesTopmostClickableRegion() {
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20).zIndex(0),
                        onClick = { events += "behind" },
                    ) { text(text = value { "Behind" }) }
                    button(
                        modifier = Modifier.offset(4, 4).size(20, 20).zIndex(1),
                        onClick = { events += "front" },
                    ) { text(text = value { "Front" }) }
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun focusedTerminalReceivesKeyEventsOnlyAfterClickAcquiresFocus() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = value { emptySnapshot() },
                        modifier = Modifier.offset(8, 8).size(80, 32),
                        onKey = { keyCode -> keyCode == 257 },
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        // No click yet — focus is not acquired.
        assertFalse(executor.isFocused)
        assertFalse(executor.keyPressed(257))

        // Click inside the terminal region acquires focus.
        assertTrue(executor.mouseClicked(10, 10))
        assertTrue(executor.isFocused)
        assertTrue(executor.keyPressed(257))
        assertFalse(executor.keyPressed(258))

        // Click outside any region drops focus again.
        assertFalse(executor.mouseClicked(500, 500))
        assertFalse(executor.isFocused)
        assertFalse(executor.keyPressed(257))
    }

    @Test
    fun charTypedAndKeyReleasedRouteThroughFocusHandler() {
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = value { emptySnapshot() },
                        modifier = Modifier.size(40, 40),
                        onKey = {
                            events += "press:$it"
                            true
                        },
                        onKeyReleased = {
                            events += "release:$it"
                            true
                        },
                        onCharTyped = {
                            events += "char:$it"
                            true
                        },
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        executor.mouseClicked(5, 5)

        assertTrue(executor.keyPressed(65))
        assertTrue(executor.keyReleased(65))
        assertTrue(executor.charTyped('A'))
        assertEquals(listOf("press:65", "release:65", "char:A"), events)
    }

    @Test
    fun keyPressedReturnsFalseWhenNoFocusableElementExists() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button({}) { text(text = value { "Noop" }) }
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
                    ) { text(text = value { "Power" }) }
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
                    If(value { shown }) {
                        button(
                            modifier = Modifier.offset(4, 4).size(20, 20),
                            onClick = { events += "hit" },
                        ) { text(text = value { "Hidden" }) }
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
                        anchor = value { anchor },
                    ) {
                        button(
                            modifier = Modifier.size(20, 20).background(Color.Red),
                            onClick = { events += "popup" },
                        ) { text(text = value { "X" }) }
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
                        anchor = value { Position(0, 0) },
                        visible = value { shown },
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

    @Test
    fun rightAlignedTextUsesCurrentDynamicTextWidthInsideFixedBounds() {
        var status = "A"
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    text(
                        modifier = Modifier.width(30).textAlign(TextAlignment.End),
                    ) {
                        status
                    }
                },
                rootWidth = 30,
                rootHeight = 9,
            )
        val executor = ScreenRuntimeExecutor(program)

        val first = RecordingBackend()
        executor.render(first)
        assertEquals(24, first.drawTexts.single().x)

        status = "ABC"
        val second = RecordingBackend()
        executor.render(second)
        assertEquals(12, second.drawTexts.single().x)
    }

    private class RecordingBackend : RenderBackend {
        data class DrawTextCall(
            val x: Int,
            val y: Int,
            val text: String,
        )

        val fillRects = mutableListOf<IntArray>()
        val drawTexts = mutableListOf<DrawTextCall>()

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
            drawTexts += DrawTextCall(x, y, text)
        }

        override fun drawTerminalSurface(
            x: Int,
            y: Int,
            snapshot: Any,
        ) {
        }

        override fun pushClip(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
        }

        override fun popClip() {
        }

        override fun drawCodeEditor(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            viewModel: ru.lazyhat.kraftui.editor.EditorViewModel,
            fontWidth: Int,
            fontHeight: Int,
        ) {
        }

        override fun measureText(text: String): Int = text.length * 6
    }

    @Test
    fun modifierFocusableAcquiresFocusOnClickAndReceivesKeyEvents() {
        val keys = mutableListOf<Int>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(
                        modifier =
                            Modifier
                                .offset(8, 8)
                                .size(40, 40)
                                .focusable(
                                    id = "editor",
                                    onKeyPressed = {
                                        keys += it
                                        true
                                    },
                                ),
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        assertFalse(executor.isFocused)
        assertTrue(executor.mouseClicked(10, 10))
        assertEquals("editor", executor.focusedNodeId)
        assertTrue(executor.keyPressed(65))
        assertEquals(listOf(65), keys)
    }

    @Test
    fun tabCyclesFocusForwardThroughTabbableNodes() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.offset(0, 0).size(20, 20).focusable(id = "first", tabOrder = 1))
                    box(modifier = Modifier.offset(30, 0).size(20, 20).focusable(id = "second", tabOrder = 2))
                    box(modifier = Modifier.offset(60, 0).size(20, 20).focusable(id = "third", tabOrder = 3))
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        executor.mouseClicked(5, 5)
        assertEquals("first", executor.focusedNodeId)

        // Plain Tab → next.
        assertTrue(executor.keyPressed(KEY_TAB))
        assertEquals("second", executor.focusedNodeId)
        assertTrue(executor.keyPressed(KEY_TAB))
        assertEquals("third", executor.focusedNodeId)
        // Wraps around.
        assertTrue(executor.keyPressed(KEY_TAB))
        assertEquals("first", executor.focusedNodeId)

        // Shift+Tab → previous.
        assertTrue(executor.keyPressed(KEY_TAB, MOD_SHIFT))
        assertEquals("third", executor.focusedNodeId)
    }

    @Test
    fun tabIsConsumedByFocusedNodeFirstAndDoesNotCycleWhenHandlerReturnsTrue() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.offset(0, 0).size(20, 20).focusable(id = "greedy", onKeyPressed = { true }))
                    box(modifier = Modifier.offset(30, 0).size(20, 20).focusable(id = "other"))
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        executor.mouseClicked(5, 5)
        assertEquals("greedy", executor.focusedNodeId)
        assertTrue(executor.keyPressed(KEY_TAB))
        assertEquals("greedy", executor.focusedNodeId)
    }

    @Test
    fun negativeTabOrderNodesAreSkippedByCycling() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.offset(0, 0).size(20, 20).focusable(id = "tabbable", tabOrder = 0))
                    box(modifier = Modifier.offset(30, 0).size(20, 20).focusable(id = "skipme", tabOrder = -1))
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        executor.mouseClicked(5, 5)
        assertEquals("tabbable", executor.focusedNodeId)
        // Only one tabbable node; Tab cycle has nothing to switch to and
        // returns false, allowing the host to handle Tab itself.
        assertFalse(executor.keyPressed(KEY_TAB))
        assertEquals("tabbable", executor.focusedNodeId)
        // The skipped node can still be focused with the mouse.
        executor.mouseClicked(35, 5)
        assertEquals("skipme", executor.focusedNodeId)
    }

    @Test
    fun restoreFocusAfterRecompileMatchesByNodeId() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.size(20, 20).focusable(id = "stable"))
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        executor.restoreFocus("stable")
        assertEquals("stable", executor.focusedNodeId)

        // Restoring an unknown id clears focus.
        executor.restoreFocus("missing")
        assertEquals(null, executor.focusedNodeId)
    }

    @Test
    fun tickValueIncrementsBetweenRendersAndIsObservedByValueExpressions() {
        val seen = mutableListOf<Int>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    text(
                        modifier = Modifier.size(40, 8),
                        text =
                            tickValue { tick ->
                                seen += tick
                                "tick=$tick"
                            },
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)
        val backend = RecordingBackend()
        executor.render(backend)
        executor.render(backend)
        executor.render(backend)

        assertEquals(3, seen.size)
        // Strictly monotonic, ticks must differ.
        assertTrue(seen[0] < seen[1])
        assertTrue(seen[1] < seen[2])
    }

    @Test
    fun draggableModifierFiresStartDragAndEndInOrder() {
        val events = mutableListOf<String>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(
                        modifier =
                            Modifier
                                .offset(0, 0)
                                .size(50, 50)
                                .draggable(
                                    onDragStart = { x, y -> events += "start:$x,$y" },
                                    onDrag = { x, y -> events += "drag:$x,$y" },
                                    onDragEnd = { x, y -> events += "end:$x,$y" },
                                ),
                    )
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        // No active drag → drag/release before press do nothing.
        assertFalse(executor.mouseDragged(10, 10))
        assertFalse(executor.mouseReleased(10, 10))

        assertTrue(executor.mouseClicked(5, 5))
        assertTrue(executor.mouseDragged(7, 8))
        assertTrue(executor.mouseDragged(9, 12))
        assertTrue(executor.mouseReleased(9, 12))

        // After release the drag is over.
        assertFalse(executor.mouseDragged(20, 20))

        assertEquals(
            listOf("start:5,5", "drag:7,8", "drag:9,12", "end:9,12"),
            events,
        )
    }

    private companion object {
        const val KEY_TAB = 258
        const val MOD_SHIFT = 0x0001
    }
}
