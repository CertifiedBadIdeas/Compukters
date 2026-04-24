package ru.lazyhat.compukterkraft.core.ui.program

import org.junit.jupiter.api.Disabled
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
import kotlin.test.assertTrue

class ScreenProgramCompilerTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    @Test
    @Disabled
    fun terminalSurfaceCompilesFocusAndKeyRouting() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    terminalSurface(
                        snapshot = { "snapshot" },
                        modifier = Modifier.offset(12, 28).size(96, 48),
                        onKey = { true },
                    )
                },
            )

        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.DrawTerminalSurface })
        assertTrue(program.inputProgram.routes.any { it.eventType == InputEventType.KeyPressed })
    }

    @Test
    fun ifNodeProducesDynamicFragmentsInsteadOfImmediateTreeRebuild() {
        var visible = false
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    If(expr { visible }) {
                        button({}) {
                            text(text = { "Shown" })
                        }
                    }
                },
            )

        assertEquals(1, program.layoutProgram.dynamicFragments.size)
        assertEquals(1, program.renderProgram.dynamicFragments.size)
    }

    @Test
    fun buttonAndTerminalUseStableHandlerIdsAcrossPrograms() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    button({}) { text(text = expr { "Power" }) }
                    terminalSurface(snapshot = expr { "snapshot" }, onKey = { true })
                },
            )

        val regionIds =
            program.hitTestProgram.regions
                .map { it.regionId }
                .toSet()
        val routedIds =
            program.inputProgram.routes
                .map { it.regionId }
                .toSet()

        assertTrue(regionIds.isNotEmpty())
        assertEquals(regionIds, routedIds)
    }

    @Test
    @Disabled
    fun terminalScreenSliceCompilesTwoControlButtonsAndOneFocusableTerminal() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    button(modifier = Modifier.offset(0, 0), {}) { text(text = expr { "Power" }) }
                    button(modifier = Modifier.offset(28, 0), {}) { text(text = expr { "Reboot" }) }
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(0, 28).size(128, 72), // .focusable(),
                        onKey = { true },
                    )
                },
            )

        assertEquals(3, program.hitTestProgram.regions.size)
        assertEquals(3, program.inputProgram.routes.size)
    }

    @Test
    fun alignedChildBoundsFlowIntoHitRegionsAndRenderLayout() {
        val compiler = ScreenProgramCompiler()

        val program =
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
            program.layoutProgram.staticNodes.single { it.nodeId == "root-0-0" },
        )
        assertEquals(
            "root-0-0",
            program.hitTestProgram.regions
                .single()
                .nodeId,
        )
    }

    @Test
    fun centeredTextUsesMeasuredBoundsInCompiledLayout() {
        val compiler = ScreenProgramCompiler(fontMetrics = fontMetrics)

        val program =
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
            program.layoutProgram.staticNodes.single { it.nodeId == "root-0" },
        )
    }
}
