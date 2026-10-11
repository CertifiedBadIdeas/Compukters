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

package ru.lazyhat.compukters.impl.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdeStatusDetailsTest {
    @Test
    fun `details scroll wrap unicode and copy complete messages while project changes close them`() {
        val details = IdeStatusDetails()
        val text = "😀 Long status information ".repeat(100) + "\nLast line"
        details.open("demo", listOf("Saved", text))
        val panel = IdeRect(0, 0, 640, 360)
        val first = assertNotNull(details.view(panel))
        assertEquals(0, first.firstRow)
        assertTrue(first.totalRows > first.rows.size)
        details.key(IdeKeyInput(IdeKeyCode.END), panel, {})
        val last = assertNotNull(details.view(panel))
        assertTrue(last.firstRow > 0)
        assertEquals("Last line", last.rows.last())
        assertTrue(last.rows.all { row -> row.codePoints().noneMatch { it in 0xD800..0xDFFF } })
        var copied = ""
        details.key(IdeKeyInput(IdeKeyCode.C, IdeModifier.CONTROL), panel) { copied = it }
        assertEquals("Saved\n\n$text", copied)
        details.press(last.copy.left + 1.0, last.copy.top + 1.0, panel) { copied = it }
        assertEquals(details.text(), copied)
        details.key(IdeKeyInput(IdeKeyCode.HOME), panel, {})
        assertEquals(0, assertNotNull(details.view(panel)).firstRow)
        details.synchronize("other")
        assertFalse(details.visible)
    }

    @Test
    fun `local overlays prioritize domain dialogs and close removed presentation`() {
        val overlays = IdeLocalOverlays({}, { true }, { _, _, _ -> }, { "" }, {})
        overlays.details.open("demo", listOf("Some detail"))
        assertTrue(overlays.active)
        overlays.synchronize(
            ru.lazyhat.compukters.ide.client.state.IdeViewState.startPage(emptyList()).copy(
                dialog =
                    ru.lazyhat.compukters.ide.client.state.IdeDialogState
                        .Confirmation("Delete", "Confirm", 1),
            ),
        )
        assertFalse(overlays.active)
        overlays.details.open("demo", listOf("Some detail"))
        overlays.close()
        assertFalse(overlays.active)
    }
}
