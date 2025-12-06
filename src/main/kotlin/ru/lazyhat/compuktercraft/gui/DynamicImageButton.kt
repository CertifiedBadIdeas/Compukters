// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

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
    private val message: Supplier<HintedMessage>,
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
        val message: HintedMessage = this.message.get()
        setMessage(message.message)
        setTooltip(message.tooltip)
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
