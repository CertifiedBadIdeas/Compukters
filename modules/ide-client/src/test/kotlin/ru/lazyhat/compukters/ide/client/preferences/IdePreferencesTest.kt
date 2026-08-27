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

package ru.lazyhat.compukters.ide.client.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdePreferencesTest {
    @Test
    fun `preferences retain only canonical remembered project and file`() {
        val valid = IdePreferences.admit("demo", "src/main.kt", 12, 4, 5, 999_999, -4, true)
        assertEquals("demo", valid.lastProjectDirectory)
        assertEquals("src/main.kt", valid.lastFile?.value)
        assertEquals(12, valid.caretUtf16)
        assertEquals(4, valid.firstVisibleLine)
        assertEquals(5, valid.firstVisibleColumn)
        assertEquals(IdePreferences.MAX_PANEL_SIZE, valid.treeWidth)
        assertEquals(IdePreferences.MIN_PANEL_SIZE, valid.diagnosticsHeight)

        val invalid = IdePreferences.admit("../escape", "../outside.kt", -1, -1, -1, 10, 10, false)
        assertNull(invalid.lastProjectDirectory)
        assertNull(invalid.lastFile)
        assertEquals(0, invalid.caretUtf16)
        assertEquals(0, invalid.firstVisibleLine)
        assertEquals(0, invalid.firstVisibleColumn)
    }
}
