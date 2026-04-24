package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.align
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.padding
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenProgramCompilerTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    @Test
    fun terminalSurfaceIsMarkedAsTheSoleFocusedElement() {
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(12, 28).size(96, 48),
                        onKey = { true },
                    )
                },
            )

        assertTrue(
            compiled.program.renderProgram.staticOps
                .any { it is RenderOp.DrawTerminalSurface },
        )
        assertEquals("root-0", compiled.program.focusedNodeId)
        assertNotNull(compiled.keyHandler)
        assertTrue(compiled.keyHandler!!.invoke(257))
    }

    @Test
    fun screensWithoutFocusableElementsHaveNullFocusAndNoKeyHandler() {
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    button({}) { text(text = expr { "Click" }) }
                },
            )

        assertNull(compiled.program.focusedNodeId)
        assertNull(compiled.keyHandler)
    }

    @Test
    fun multipleFocusableElementsAreRejectedAtCompileTime() {
        val compiler = ScreenProgramCompiler()

        val error =
            assertFailsWith<IllegalStateException> {
                compiler.compile(
                    ui {
                        terminalSurface(snapshot = expr { "a" }, onKey = { true })
                        terminalSurface(snapshot = expr { "b" }, onKey = { true })
                    },
                )
            }
        assertTrue(error.message!!.contains("multiple focusable elements"))
    }

    @Test
    fun ifNodeProducesDynamicFragmentsInsteadOfImmediateTreeRebuild() {
        var visible = false
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    If(expr { visible }) {
                        button({}) {
                            text(text = { "Shown" })
                        }
                    }
                },
            )

        assertEquals(1, compiled.program.layoutProgram.dynamicFragments.size)
        assertEquals(1, compiled.program.renderProgram.dynamicFragments.size)
    }

    @Test
    fun buttonClickHandlerIsExtractedAndKeyedByRegion() {
        var pressed = false
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    button(onClick = { pressed = true }) { text(text = expr { "Power" }) }
                },
            )

        val regionIds =
            compiled.program.hitTestProgram.regions
                .map { it.regionId }
                .toSet()
        val routedIds =
            compiled.program.inputProgram.routes
                .map { it.regionId }
                .toSet()
        assertEquals(regionIds, routedIds)
        assertEquals(1, compiled.clickHandlers.size)

        compiled.clickHandlers.values
            .single()
            .invoke()
        assertTrue(pressed)
    }

    @Test
    fun alignedChildBoundsFlowIntoHitRegionsAndRenderLayout() {
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    box(modifier = Modifier.size(200, 120).padding(10)) {
                        button(
                            modifier = Modifier.size(80, 20).align(UiAlignment.Center),
                            {},
                        ) { text(text = expr { "Centered" }) }
                    }
                },
            )

        assertEquals(
            LayoutNode("root-0-0", 60, 50, 80, 20),
            compiled.program.layoutProgram.staticNodes
                .single { it.nodeId == "root-0-0" },
        )
        assertEquals(
            "root-0-0",
            compiled.program.hitTestProgram.regions
                .single()
                .nodeId,
        )
    }

    @Test
    fun centeredTextUsesMeasuredBoundsInCompiledLayout() {
        val compiler = ScreenProgramCompiler(fontMetrics = fontMetrics)

        val compiled =
            compiler.compile(
                ui(Modifier.size(100, 40)) {
                    text(
                        modifier = Modifier.align(UiAlignment.Center),
                        text = expr { "AB" },
                    )
                },
            )

        assertEquals(
            LayoutNode("root-0", 44, 15, 12, 9),
            compiled.program.layoutProgram.staticNodes
                .single { it.nodeId == "root-0" },
        )
    }
}
