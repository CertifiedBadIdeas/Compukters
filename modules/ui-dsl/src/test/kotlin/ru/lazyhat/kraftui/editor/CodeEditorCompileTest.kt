package ru.lazyhat.kraftui.editor

import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.offset
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import ru.lazyhat.kraftui.program.RenderOp
import ru.lazyhat.kraftui.program.ScreenProgramCompiler
import ru.lazyhat.kraftui.program.ScreenRuntimeExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeEditorCompileTest {
    private class FakeViewModel(
        override val text: String = "hello\nworld",
        override val cursorLine: Int = 0,
        override val cursorColumn: Int = 0,
        override val scrollLine: Int = 0,
        override val highlights: List<HighlightToken> = emptyList(),
        override val diagnostics: List<Diagnostic> = emptyList(),
        override val selection: SelectionRange? = null,
    ) : EditorViewModel {
        val keyEvents = mutableListOf<Triple<Int, Int, Int>>()
        val charEvents = mutableListOf<Pair<Char, Int>>()
        val clickEvents = mutableListOf<Pair<Int, Int>>()
        val scrollEvents = mutableListOf<Int>()

        override fun onKeyPressed(
            key: Int,
            modifiers: Int,
            visibleLines: Int,
        ): Boolean {
            keyEvents += Triple(key, modifiers, visibleLines)
            return true
        }

        override fun onCharTyped(
            ch: Char,
            visibleLines: Int,
        ): Boolean {
            charEvents += ch to visibleLines
            return true
        }

        override fun onMouseClickAt(
            line: Int,
            column: Int,
        ) {
            clickEvents += line to column
        }

        override fun onScroll(deltaLines: Int) {
            scrollEvents += deltaLines
        }
    }

    @Test
    fun codeEditorEmitsDrawCodeEditorOpAndAutoRegistersFocusHitAndScroll() {
        val vm = FakeViewModel()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    codeEditor(
                        viewModel = value { vm },
                        modifier = Modifier.offset(10, 20).size(120, 90),
                        fontWidth = 6,
                        fontHeight = 9,
                    )
                },
            )

        val op =
            program.frames
                .single()
                .ops
                .single() as RenderOp.DrawCodeEditor
        assertEquals(10, op.x)
        assertEquals(20, op.y)
        assertEquals(120, op.width)
        assertEquals(90, op.height)
        assertEquals(6, op.fontWidth)
        assertEquals(9, op.fontHeight)
        assertSame(vm, op.viewModel.value)

        val focus = program.focusNodes.single()
        val hit = program.hitRegions.single()
        val scroll = program.scrollRegions.single()
        assertEquals(focus.nodeId, hit.nodeId)
        assertEquals(focus.nodeId, scroll.nodeId)
    }

    @Test
    fun codeEditorRoutesKeyAndCharEventsThroughViewModel() {
        val vm = FakeViewModel()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    codeEditor(
                        viewModel = value { vm },
                        modifier = Modifier.offset(0, 0).size(60, 90),
                        fontWidth = 6,
                        fontHeight = 9,
                    )
                },
            )
        val executor = ScreenRuntimeExecutor(program)

        // Click inside grants focus to the editor.
        assertTrue(executor.mouseClicked(5, 5))

        executor.keyPressed(257)
        executor.charTyped('a')

        assertEquals(Triple(257, 0, 10), vm.keyEvents.single())
        assertEquals('a' to 10, vm.charEvents.single())
    }

    @Test
    fun codeEditorMouseClickConvertsPixelsToLineAndColumn() {
        val vm = FakeViewModel(text = "abcdef\nghijkl", scrollLine = 0)
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    codeEditor(
                        viewModel = value { vm },
                        modifier = Modifier.offset(0, 0).size(80, 90),
                        fontWidth = 6,
                        fontHeight = 9,
                    )
                },
            )
        val executor = ScreenRuntimeExecutor(program)

        // Gutter for two-line buffer = (1 digit + 1 padding) * 6 = 12 px.
        // localY = 12 → line = 0 + (12 / 9) = 1.
        // localX = 12 + 3 * 6 = 30 → column = 3.
        assertTrue(executor.mouseClicked(30, 12))
        assertEquals(1 to 3, vm.clickEvents.last())
    }

    @Test
    fun codeEditorWheelEventsForwardLineDeltaToViewModel() {
        val vm = FakeViewModel()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    codeEditor(
                        viewModel = value { vm },
                        modifier = Modifier.offset(0, 0).size(60, 90),
                        fontWidth = 6,
                        fontHeight = 9,
                    )
                },
            )
        val executor = ScreenRuntimeExecutor(program)

        assertTrue(executor.mouseScrolled(5, 5, deltaY = -1.0))
        assertTrue(executor.mouseScrolled(5, 5, deltaY = 1.0))
        assertEquals(listOf(1, -1), vm.scrollEvents)

        // Wheel events outside the editor are ignored.
        val ignored = executor.mouseScrolled(-5, -5, deltaY = 1.0)
        assertTrue(!ignored)
    }

    @Test
    fun codeEditorMetricsGutterWidthMatchesDigitCount() {
        assertEquals(2 * 6, CodeEditorMetrics.gutterPixelWidth(1, 6))
        assertEquals(2 * 6, CodeEditorMetrics.gutterPixelWidth(9, 6))
        assertEquals(3 * 6, CodeEditorMetrics.gutterPixelWidth(10, 6))
        assertEquals(3 * 6, CodeEditorMetrics.gutterPixelWidth(99, 6))
        assertEquals(4 * 6, CodeEditorMetrics.gutterPixelWidth(100, 6))
        assertNotNull(CodeEditorMetrics.lineCount("abc"))
        assertEquals(1, CodeEditorMetrics.lineCount(""))
        assertEquals(1, CodeEditorMetrics.lineCount("abc"))
        assertEquals(3, CodeEditorMetrics.lineCount("a\nb\nc"))
    }
}
