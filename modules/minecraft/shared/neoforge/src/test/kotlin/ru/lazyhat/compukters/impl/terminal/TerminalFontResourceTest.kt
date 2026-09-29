/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.terminal

import java.awt.Font
import java.awt.font.FontRenderContext
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalFontResourceTest {
    @Test
    fun `JetBrains Mono terminal uses the pinned face and honest coverage`() {
        val face =
            runtimeResource("/assets/compukters/font/ide/jetbrains_mono_regular.ttf")
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }
        val profile = TerminalFontProfile
        val context = FontRenderContext(null, true, true)
        // AWT synthesizes invisible glyph 0xffff for default-ignorable characters regardless of the cmap.
        // Compare the full visible repertoire; controls and these synthetic glyphs cannot validate a font cmap.
        for (codePoint in 32..Character.MAX_CODE_POINT) {
            if (!Character.isISOControl(codePoint)) {
                val present = face.canDisplay(codePoint)
                if (present && face.createGlyphVector(context, Character.toChars(codePoint)).getGlyphCode(0) == 0xffff) continue
                assertEquals(present, profile.supports(codePoint), "U+${codePoint.toString(16)}")
            }
        }
        listOf('Ж', 'Ё', '←', '↑', '→', '↓', '─', '│', '┼', '█').forEach { glyph ->
            assertEquals(glyph.code, profile.renderCodePoint(glyph.code))
        }
        assertEquals(0xFFFD, profile.renderCodePoint(0x1F680))
    }

    private fun runtimeResource(path: String) =
        requireNotNull(TerminalFontResourceTest::class.java.getResourceAsStream(path)) {
            "Missing runtime resource $path"
        }
}
