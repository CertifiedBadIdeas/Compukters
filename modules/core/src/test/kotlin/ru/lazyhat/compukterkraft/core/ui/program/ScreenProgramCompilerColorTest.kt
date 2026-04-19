package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class ScreenProgramCompilerColorTest {
    @Test
    fun backgroundColorCompilesBoxToFillRectWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                box(modifier = Modifier.size(40, 20).backgroundColor(Color.Red)) { }
            },
        )

        assertEquals(
            listOf(RenderOp.FillRect("root-0", Color.Red)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun textColorCompilesTextToDrawTextWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                text(
                    value = textExpr { "Hello" },
                    modifier = Modifier.textColor(Color.Green),
                )
            },
        )

        assertEquals(
            listOf(RenderOp.DrawText("root-0", "Hello", Color.Green)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun legacyColorStillCompilesBoxToFillRect() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                box(modifier = Modifier.size(40, 20).color(Color.Red)) { }
            },
        )

        assertEquals(
            listOf(RenderOp.FillRect("root-0", Color.Red)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun legacyColorStillCompilesTextToDrawText() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                text(
                    value = textExpr { "Hello" },
                    modifier = Modifier.color(Color.Blue),
                )
            },
        )

        assertEquals(
            listOf(RenderOp.DrawText("root-0", "Hello", Color.Blue)),
            program.renderProgram.staticOps,
        )
    }
}