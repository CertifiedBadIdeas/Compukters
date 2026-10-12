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
    fun `window painter keeps shared button surfaces and borders visible above picker background`() {
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, IdeCodeFontProfile.DEFAULT)
        for (view in listOf(
            IdePeripheralPickerView(),
            IdePeripheralPickerView(loading = true),
            IdePeripheralPickerView(listOf(IdeNamedPeripheral("speed", "create:speedometer")), writable = true),
        )) {
            val output = IdeDrawBuilder(geometry.font)
            IdePeripheralPicker.draw(output, geometry.content, view)
            val model = output.build()
            val fills = mutableListOf<Pair<IdeRect, Int>>()
            val canvas =
                object : IdeCanvas {
                    override fun fill(
                        bounds: IdeRect,
                        color: Int,
                    ) {
                        fills += bounds to color
                    }

                    override fun icon(draw: IdeIconDraw) = Unit

                    override fun text(draw: IdeTextDraw) = Unit

                    override fun terminal(
                        state: ru.lazyhat.compukters.lang.runtime.vm.TerminalState,
                        geometry: ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry,
                        nowMillis: Long,
                    ) = Unit
                }
            val frame =
                IdeScreenFrame(
                    ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
                        .admitIde(1920, 1080, 2),
                    geometry,
                    model,
                    ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState.Closed,
                    false,
                    false,
                )
            IdeFramePainter.paint(frame, canvas, 0)

            fun pixel(
                x: Int,
                y: Int,
            ) = fills
                .last { (bounds, _) ->
                    x >= bounds.left && x < bounds.right && y >= bounds.top &&
                        y < bounds.bottom
                }.second
            for (target in model.hitTargets) {
                assertEquals(
                    if (target.enabled) IdeColors.CONTROL else IdeColors.PANEL_ALT,
                    pixel(
                        target.bounds.left + 2,
                        target.bounds.top + 2,
                    ),
                    target.action.name,
                )
                assertEquals(
                    if (target.enabled) IdeColors.CONTROL_BORDER else IdeColors.BORDER,
                    pixel(target.bounds.left, target.bounds.top),
                    target.action.name,
                )
            }
        }
    }

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
