package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.UiRole
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenProgramCompilerTest {
    @Test
    fun buttonSugarCompilesToRenderHitInputAndFocusPrograms() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    button(
                        text = textExpr { "Power" },
                        modifier = Modifier.offset(8, 8),
                    ) { }
                },
            )

        assertEquals(1, program.hitTestProgram.regions.size)
        assertEquals(
            UiRole.Button,
            program.hitTestProgram.regions
                .single()
                .role,
        )
        assertTrue(program.inputProgram.routes.any { it.eventType == InputEventType.Click })
        assertTrue(program.focusProgram.targets.any { it.role == UiRole.Button })
        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.FillRect })
        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.DrawText })
    }

    @Test
    fun terminalSurfaceCompilesFocusAndKeyRouting() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(12, 28).size(96, 48).focusable(),
                        onKey = { true },
                    )
                },
            )

        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.DrawTerminalSurface })
        assertTrue(program.inputProgram.routes.any { it.eventType == InputEventType.KeyPressed })
        assertTrue(program.focusProgram.targets.any { it.role == UiRole.TerminalSurface })
    }

    @Test
    fun ifNodeProducesDynamicFragmentsInsteadOfImmediateTreeRebuild() {
        var visible = false
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    If(expr { visible }) {
                        button(text = textExpr { "Shown" }) { }
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
                    button(text = textExpr { "Power" }) { }
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
    fun terminalScreenSliceCompilesTwoControlButtonsAndOneFocusableTerminal() {
        val compiler = ScreenProgramCompiler()

        val program =
            compiler.compile(
                ui {
                    button(text = textExpr { "Power" }, modifier = Modifier.offset(0, 0)) { }
                    button(text = textExpr { "Reboot" }, modifier = Modifier.offset(28, 0)) { }
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(0, 28).size(128, 72).focusable(),
                        onKey = { true },
                    )
                },
            )

        assertEquals(3, program.hitTestProgram.regions.size)
        assertEquals(3, program.inputProgram.routes.size)
        assertEquals(3, program.focusProgram.targets.size)
    }
}
