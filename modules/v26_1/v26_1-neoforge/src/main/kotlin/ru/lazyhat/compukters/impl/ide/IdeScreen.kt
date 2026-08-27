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

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig

internal class IdeScreen(
    private val application: IdeClientApplication,
) : Screen(Component.literal("Compukters IDE")) {
    override fun setInitialFocus() = Unit

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val handled = super.mouseClicked(event, doubleClick)
        if (handled) clearFocus()
        return handled
    }

    override fun tick() {
        application.controller.tick()
        super.tick()
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val profile = CompuktersClientConfig.selectedFont()
        val layout = application.preferences.layout()
        val geometry =
            IdeRenderGeometry.compute(
                width,
                height,
                layout.padding,
                layout.treeWidth,
                layout.diagnosticsHeight,
                layout.diagnosticsExpanded,
                treeVisible = true,
                profile,
            )
        val model = IdeRenderer.extract(application.controller.viewState(), geometry)
        val operations = mutableListOf<RenderOperation>()
        model.panels.forEach { draw -> operations += RenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.fills.forEach { draw -> operations += RenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.text.forEach { draw ->
            operations +=
                RenderOperation(draw.zIndex) {
                    draw.clip?.let { graphics.enableScissor(it.left, it.top, it.right, it.bottom) }
                    val value =
                        if (draw.codeFont == null) {
                            Component.literal(draw.value)
                        } else {
                            Component.literal(draw.value).withStyle { style -> style.withFont(draw.codeFont.fontDescription) }
                        }
                    graphics.text(font, value, draw.x, draw.y, draw.color, false)
                    if (draw.clip != null) graphics.disableScissor()
                }
        }
        operations.sortedBy(RenderOperation::zIndex).forEach { it.draw() }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    private fun GuiGraphicsExtractor.fill(
        bounds: IdeRect,
        color: Int,
    ) {
        fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
    }

    private class RenderOperation(
        val zIndex: Int,
        val draw: () -> Unit,
    )
}
