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

class IdeRenderLayersTest {
    @Test
    fun `project surface covers the Devices icon at the final painted pixel`() {
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, IdeCodeFontProfile.DEFAULT)
        val icon = IdeIconDraw(IdeIconKind.Resolve, IdeRect(40, 40, 56, 56), IdeColors.TEXT, 30, "compukters:display")
        val model =
            IdeDrawModel(
                panels = listOf(IdePanelDraw(IdePanelKind.ProjectSwitcher, IdeRect(20, 20, 100, 100), IdeColors.PANEL, 70)),
                text = emptyList(),
                fills = emptyList(),
                scissors = emptyList(),
                hitTargets = emptyList(),
                icons = listOf(icon),
            )
        var layer = 0
        var pixelDepth = Float.NEGATIVE_INFINITY
        var pixelColor = 0
        val canvas =
            object : IdeCanvas {
                override fun layer(zIndex: Int) {
                    layer = zIndex
                }

                override fun fill(
                    bounds: IdeRect,
                    color: Int,
                ) {
                    if (bounds.containsPoint(48.0, 48.0) && layer >= pixelDepth) {
                        pixelDepth = layer.toFloat()
                        pixelColor = color
                    }
                }

                override fun icon(draw: IdeIconDraw) {
                    // The item model has a small depth extent within the current backend layer.
                    if (draw.bounds.containsPoint(48.0, 48.0) && layer + 0.08f >= pixelDepth) {
                        pixelDepth = layer + 0.08f
                        pixelColor = draw.color
                    }
                }

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
        assertEquals(IdeColors.PANEL, pixelColor)
    }

    @Test
    fun `backend layers enclose native icons and every floating surface in sorted order`() {
        val events = mutableListOf<String>()
        executeIdeRenderOperations(
            mutableListOf(
                IdeRenderOperation(70) { events += "project window" },
                IdeRenderOperation(30) { events += "Devices item" },
                IdeRenderOperation(100) { events += "dialog" },
                IdeRenderOperation(20) { events += "toolbar button" },
                IdeRenderOperation(30) { events += "toolbar text" },
                IdeRenderOperation(90) { events += "scrim" },
            ),
            terminalVisible = true,
            beginLayer = { events += "layer $it" },
            renderTerminal = { events += "terminal" },
        )
        assertEquals(
            listOf(
                "layer 20",
                "toolbar button",
                "layer 30",
                "Devices item",
                "toolbar text",
                "layer 70",
                "project window",
                "layer 80",
                "terminal",
                "layer 90",
                "scrim",
                "layer 100",
                "dialog",
            ),
            events,
        )
    }
}
