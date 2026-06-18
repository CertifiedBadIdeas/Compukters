/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.kraftui.fixture

import ru.lazyhat.kraftui.editor.EditorViewModel
import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.background
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import ru.lazyhat.kraftui.program.RenderBackend
import ru.lazyhat.kraftui.program.ScreenProgramCompiler
import ru.lazyhat.kraftui.program.ScreenRuntimeExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneUiDslConsumerTest {
    @Test
    fun externalConsumerCanCompileAndRenderDslProgram() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(
                        modifier = Modifier.size(64, 24).background(Color.rgb(12, 24, 48)),
                    ) {
                        text(text = value { "Hello DSL" }, color = Color.White)
                    }
                },
            )
        val backend = RecordingBackend()

        ScreenRuntimeExecutor(program).render(backend)

        assertEquals(listOf(Rect(0, 0, 64, 24, Color.rgb(12, 24, 48))), backend.rects)
        assertTrue(TextDraw(0, 0, "Hello DSL", Color.White) in backend.texts)
    }
}

private data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val color: Color,
)

private data class TextDraw(
    val x: Int,
    val y: Int,
    val text: String,
    val color: Color,
)

private class RecordingBackend : RenderBackend {
    val rects = mutableListOf<Rect>()
    val texts = mutableListOf<TextDraw>()

    override fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Color,
    ) {
        rects += Rect(x, y, width, height, color)
    }

    override fun drawText(
        x: Int,
        y: Int,
        text: String,
        color: Color,
    ) {
        texts += TextDraw(x, y, text, color)
    }

    override fun drawTerminalSurface(
        x: Int,
        y: Int,
        snapshot: Any,
    ) = Unit

    override fun pushClip(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun popClip() = Unit

    override fun drawCodeEditor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        viewModel: EditorViewModel,
        fontWidth: Int,
        fontHeight: Int,
    ) = Unit

    override fun measureText(text: String): Int = text.length * 6
}
