package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenProgramCompilerColorTest {
    @Test
    fun backgroundColorCompilesBoxToFillRectWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    box(modifier = Modifier.size(40, 20).background(Color.Red)) { }
                },
            )

        assertEquals(
            listOf(RenderOp.FillRect("root-0", Color.Red)),
            compiled.program.renderProgram.staticOps,
        )
    }

    @Test
    fun textColorCompilesTextToDrawTextWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val compiled =
            compiler.compile(
                ui {
                    text(
                        text = { "Hello" },
                        color = Color.Green,
                    )
                },
            )

        assertEquals(
            listOf(RenderOp.DrawText("root-0", "Hello", Color.Green)),
            compiled.program.renderProgram.staticOps,
        )
    }
}
