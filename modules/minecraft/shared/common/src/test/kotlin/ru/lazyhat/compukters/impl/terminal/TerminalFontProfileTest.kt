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

import ru.lazyhat.compukters.impl.ide.IdeCodeFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalFontProfileTest {
    @Test
    fun `default profile exposes fixed JetBrains Mono metrics`() {
        val profile = TerminalFontProfile

        assertEquals("jetbrains_mono", profile.id)
        assertEquals(6, profile.cellWidth)
        assertEquals(13, profile.cellHeight)
        assertEquals(10, profile.ascent)
        assertEquals(3, profile.glyphDrawOffsetY)
        assertEquals(0xFFFD, profile.replacementCodePoint)
        assertEquals(IdeCodeFontProfile.DEFAULT.cellWidth, profile.cellWidth)
        assertEquals(IdeCodeFontProfile.DEFAULT.cellHeight, profile.cellHeight)
        assertEquals(IdeCodeFontProfile.DEFAULT.baseline, profile.ascent)
    }

    @Test
    fun `fallback is deterministic and never indexes an unsupported code point`() {
        val profile = TerminalFontProfile

        assertTrue(profile.supports('Ж'.code))
        assertFalse(profile.supports(0x1F680))
        assertEquals('Ж'.code, profile.renderCodePoint('Ж'.code))
        assertEquals(0xFFFD, profile.renderCodePoint(0x1F680))
        assertEquals(0xFFFD, profile.renderCodePoint(-1))
    }
}
