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
package ru.lazyhat.compukterkraft.gui

import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.booleans.Boolean2ObjectFunction
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.network.chat.Component
import java.util.function.Supplier

/**
 * Version of [net.minecraft.client.gui.components.ImageButton] which allows changing some properties
 * dynamically.
 */
class DynamicImageButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val texture: Boolean2ObjectFunction<TextureAtlasSprite>,
    onPress: OnPress,
    private val hintedMessageLambda: Supplier<HintedMessage>,
) : Button(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION) {
    constructor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        texture: Boolean2ObjectFunction<TextureAtlasSprite>,
        onPress: OnPress,
        message: HintedMessage?,
    ) : this(x, y, width, height, texture, onPress, Supplier { message!! })

    public override fun renderWidget(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        val texture = this.texture.get(isHoveredOrFocused())

        RenderSystem.disableDepthTest()
        graphics.blit(getX(), getY(), 0, width, height, texture)
        RenderSystem.enableDepthTest()
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        hintedMessageLambda.get().let { hMessage ->
            message = hMessage.message!!
            tooltip = hMessage.tooltip
        }
        super.render(graphics, mouseX, mouseY, partialTicks)
    }

    @JvmRecord
    data class HintedMessage(
        val message: Component?,
        val tooltip: Tooltip?,
    ) {
        constructor(
            message: Component,
            hint: Component?,
        ) : this(
            message,
            if (hint == null) {
                Tooltip.create(message)
            } else {
                Tooltip.create(
                    Component
                        .empty()
                        .append(message)
                        .append("\n")
                        .append(hint.copy().withStyle(ChatFormatting.GRAY)),
                    hint,
                )
            },
        )
    }
}
