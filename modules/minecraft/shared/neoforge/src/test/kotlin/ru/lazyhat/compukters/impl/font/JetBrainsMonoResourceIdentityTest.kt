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

package ru.lazyhat.compukters.impl.font

import ru.lazyhat.compukters.impl.ide.IdeCodeFontProfile
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import ru.lazyhat.compukters.impl.ide.fontDescription as editorFontDescription
import ru.lazyhat.compukters.impl.terminal.fontDescription as terminalFontDescription

class JetBrainsMonoResourceIdentityTest {
    @Test
    fun `editor and terminal use one font ID without a parallel-warmed TTF alias`() {
        assertEquals(IdeCodeFontProfile.DEFAULT.editorFontDescription, TerminalFontProfile.JETBRAINS_MONO.terminalFontDescription)
        assertNull(javaClass.getResource("/assets/compukters/font/terminal/jetbrains_mono.json"))
    }

    @Test
    fun `bitmap preferences retain independent font IDs`() {
        listOf(TerminalFontProfile.COZETTE, TerminalFontProfile.DINA, TerminalFontProfile.PROGGY_TINY).forEach { profile ->
            assertNotEquals(IdeCodeFontProfile.DEFAULT.editorFontDescription, profile.terminalFontDescription)
        }
    }
}
