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
            IdePeripheralPickerView(
                listOf(IdeNamedPeripheral("speed", "create:speedometer", lookupTypes = listOf("create.kinetics.Speedometer"))),
                selectedIndex = 0,
            ),
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
                    if (target.selected) {
                        IdeColors.SELECTION
                    } else if (target.enabled) {
                        IdeColors.CONTROL
                    } else {
                        IdeColors.PANEL_ALT
                    },
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
    fun `picker draws project sized cards and permits selecting ambiguous names`() {
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
        assertTrue(rows[1].enabled)
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
    fun `device actions work without a writable file and typed lookup escapes Kotlin templates`() {
        val entry =
            IdeNamedPeripheral(
                "screen$" + "name",
                "compukters:display",
                reference =
                    ru.lazyhat.compukters.impl.ide.target.IdePeripheralReference(
                        java.util.UUID.randomUUID(),
                        "compukters-display",
                        "text",
                    ),
                lookupTypes = listOf("compukter.display.TextDisplay", "compukter.display.GraphicalDisplay"),
            )
        val view = IdePeripheralPickerView(listOf(entry), selectedIndex = 0)
        val output = IdeDrawBuilder(IdeCodeFontProfile.DEFAULT)
        val area = IdeRect(0, 0, 800, 500)
        IdePeripheralPicker.draw(output, area, view)
        val actions = output.build().hitTargets.associateBy { it.action }
        assertTrue(actions.getValue(IdeHitAction.PeripheralRename).enabled)
        assertTrue(actions.getValue(IdeHitAction.PeripheralCopyLookup).enabled)
        assertTrue(actions.getValue(IdeHitAction.PeripheralLookupType).enabled)
        assertFalse(actions.getValue(IdeHitAction.PeripheralInsert).enabled)
        assertTrue(actions.values.all { IdePeripheralPicker.bounds(area).contains(it.bounds) })
        val literal =
            ru.lazyhat.compukters.ide.editor.PeripheralConstantInsertion
                .quote(entry.name)
        assertEquals("compukter.display.TextDisplay.named($literal)", IdePeripheralPicker.lookup(view))
        assertTrue(literal.contains('\\'))
        assertEquals("compukter.display.GraphicalDisplay.named($literal)", IdePeripheralPicker.lookup(view.copy(lookupIndex = 1)))
        assertEquals(null, IdePeripheralPicker.lookup(view.copy(entries = listOf(entry.copy(duplicate = true)))))
        assertEquals(null, IdePeripheralPicker.lookup(view.copy(entries = listOf(entry.copy(lookupTypes = emptyList())))))
    }

    @Test
    fun `copy name requires selection but allows duplicate untyped devices and action rows fit narrow windows`() {
        val entry = IdeNamedPeripheral("device_1", "minecraft:stone", duplicate = true)
        val selected = IdePeripheralPickerView(listOf(entry), selectedIndex = 0)
        for (area in listOf(IdeRect(0, 0, 800, 500), IdeRect(0, 0, 248, 208))) {
            for (view in listOf(selected, selected.copy(selectedIndex = null), selected.copy(loading = true))) {
                val output = IdeDrawBuilder(IdeCodeFontProfile.DEFAULT)
                IdePeripheralPicker.draw(output, area, view)
                val actions = output.build().hitTargets
                val copy = actions.single { it.action == IdeHitAction.PeripheralCopyName }
                assertEquals(view.selectedIndex != null && !view.loading, copy.enabled)
                assertFalse(actions.single { it.action == IdeHitAction.PeripheralCopyLookup }.enabled)
                assertTrue(actions.all { IdePeripheralPicker.bounds(area).contains(it.bounds) })
                val buttons =
                    actions.filter {
                        it.action in
                            setOf(
                                IdeHitAction.PeripheralCopyName,
                                IdeHitAction.PeripheralCopyLookup,
                                IdeHitAction.PeripheralInsert,
                                IdeHitAction.PeripheralRename,
                            )
                    }
                for ((index, button) in buttons.withIndex()) {
                    assertTrue(
                        buttons.drop(index + 1).none { other ->
                            button.bounds.left < other.bounds.right && other.bounds.left < button.bounds.right &&
                                button.bounds.top < other.bounds.bottom && other.bounds.top < button.bounds.bottom
                        },
                    )
                }
            }
        }
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
