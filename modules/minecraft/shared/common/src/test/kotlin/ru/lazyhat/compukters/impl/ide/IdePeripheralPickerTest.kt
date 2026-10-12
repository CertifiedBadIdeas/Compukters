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

import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdePeripheralPickerTest {
    @Test
    fun `picker draws project sized cards doubled icons and disables ambiguous names`() {
        val output = IdeDrawBuilder(IdeCodeFontProfile.DEFAULT)
        val area = IdeRect(0, 0, 800, 500)
        IdePeripheralPicker.draw(
            output,
            area,
            IdePeripheralPickerView(
                listOf(
                    IdeNamedPeripheral("speed", "create:speedometer"),
                    IdeNamedPeripheral("duplicate", "compukters:display", true),
                ),
                writable = true,
            ),
        )
        val model = output.build()
        assertTrue(model.panels.any { it.kind == IdePanelKind.PeripheralSelection })
        val rows = model.hitTargets.filter { it.action == IdeHitAction.PeripheralChoice }
        assertEquals(38, rows[0].bounds.height)
        assertTrue(rows[0].enabled)
        assertFalse(rows[1].enabled)
        assertEquals(
            32,
            model.icons
                .first()
                .bounds.width,
        )
        assertEquals(
            32,
            model.icons
                .first()
                .bounds.height,
        )
        assertEquals("create:speedometer", model.icons.first().itemId)
    }

    @Test
    fun `scroll window bounds rows and keeps full device indices`() {
        val output = IdeDrawBuilder(IdeCodeFontProfile.DEFAULT)
        val area = IdeRect(0, 0, 800, 500)
        val entries = List(100) { IdeNamedPeripheral("device_$it", "minecraft:stone") }
        IdePeripheralPicker.draw(output, area, IdePeripheralPickerView(entries, firstRow = 30, writable = true))
        val rows = output.build().hitTargets.filter { it.action == IdeHitAction.PeripheralChoice }
        assertEquals(IdePeripheralPicker.visibleRows(area), rows.size)
        assertEquals(30, rows.first().choiceIndex)
        assertTrue(rows.all { IdePeripheralPicker.bounds(area).contains(it.bounds) })
    }
}
