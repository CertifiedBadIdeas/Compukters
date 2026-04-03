/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ck.mod.ui.dsl

import ck.mod.ui.render.FixedWidthFontRenderer
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType

/**
 * Converts a list of [UiNode] into actual draw calls via [GuiGraphics].
 *
 * This is the **only** place that knows about Minecraft's rendering API.
 * All Blaze3D / OpenGL interaction is isolated here and in [FixedWidthFontRenderer].
 */
object UiRenderer {
    fun render(
        graphics: GuiGraphics,
        font: Font,
        nodes: List<UiNode>,
    ) {
        for (node in nodes) {
            renderNode(graphics, font, node)
        }
    }

    private fun renderNode(
        graphics: GuiGraphics,
        font: Font,
        node: UiNode,
    ) {
        when (node) {
            is Rect -> {
                graphics.fill(node.x, node.y, node.x + node.w, node.y + node.h, node.color)
            }

            is Text -> {
                graphics.drawString(font, node.text, node.x, node.y, node.color, node.shadow)
            }

            is RightAlignedText -> {
                val textWidth = font.width(node.text)
                val drawX = node.x + node.areaWidth - textWidth
                graphics.drawString(font, node.text, drawX, node.y, node.color, node.shadow)
            }

            is TerminalView -> {
                val renderType = RenderType.text(FixedWidthFontRenderer.FONT)
                val bufferSource = MultiBufferSource.immediate(ByteBufferBuilder(renderType.bufferSize()))
                val emitter =
                    FixedWidthFontRenderer.toVertexConsumer(
                        graphics.pose(),
                        bufferSource.getBuffer(renderType),
                    )
                FixedWidthFontRenderer.drawTerminal(
                    emitter,
                    node.x.toFloat(),
                    node.y.toFloat(),
                    node.snapshot,
                    0f,
                    0f,
                    0f,
                    0f,
                )
                bufferSource.endBatch()
            }

            is Group -> {
                render(graphics, font, node.children)
            }
        }
    }
}
