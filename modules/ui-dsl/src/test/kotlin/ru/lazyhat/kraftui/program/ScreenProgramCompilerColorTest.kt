package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.background
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenProgramCompilerColorTest {
    @Test
    fun backgroundColorCompilesBoxToFillRectWithItsOwnColor() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.size(40, 20).background(Color.Red)) { }
                },
            )

        val fill =
            program.frames[0]
                .ops
                .filterIsInstance<RenderOp.FillRect>()
                .single()
        assertEquals(Color.Red, fill.color)
        assertEquals(40, fill.width)
        assertEquals(20, fill.height)
    }

    @Test
    fun textColorCompilesTextToDrawTextWithItsOwnColor() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    text(
                        text = value { "Hello" },
                        color = Color.Green,
                    )
                },
            )

        val text =
            program.frames[0]
                .ops
                .filterIsInstance<RenderOp.DrawText>()
                .single()
        assertEquals(Color.Green, text.color.value)
        assertEquals("Hello", text.value.value)
    }
}
