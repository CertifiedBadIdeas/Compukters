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

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastComponent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.min

/**
 * A [Toast] implementation which displays an arbitrary message along with an optional [ItemStack].
 */
class ItemToast(
    minecraft: Minecraft,
    private val stack: ItemStack,
    private val title: Component,
    message: Component,
    private val token: Any,
) : Toast {
    private val message: MutableList<FormattedCharSequence?>
    private val width: Int

    private var isNew = true
    private var firstDisplay: Long = 0

    init {
        val font = minecraft.font
        this.message = font.split(message, MAX_LINE_SIZE)
        width = max(
            MAX_LINE_SIZE,
            this.message.stream().mapToInt { text: FormattedCharSequence? -> font.width(text) }.max().orElse(
                MAX_LINE_SIZE,
            ),
        ) + MARGIN * 3 + IMAGE_SIZE
    }

    fun showOrReplace(toasts: ToastComponent) {
        val existing = toasts.getToast(ItemToast::class.java, token)
        if (existing != null) {
            existing.isNew = true
        } else {
            toasts.addToast(this)
        }
    }

    override fun width(): Int = width

    override fun height(): Int = MARGIN * 2 + LINE_SPACING + message.size * LINE_SPACING

    override fun getToken(): Any = token

    override fun render(
        graphics: GuiGraphics,
        component: ToastComponent,
        time: Long,
    ): Toast.Visibility {
        if (isNew) {
            firstDisplay = time
            isNew = false
        }

        if (width == 160 && message.size <= 1) {
            graphics.blit(Toast.TEXTURE, 0, 0, 0, 64, width, height())
        } else {
            val height = height()

            val bottom = min(4, height - 28)
            renderBackgroundRow(graphics, width, 0, 0, 28)

            var i = 28
            while (i < height - bottom) {
                renderBackgroundRow(graphics, width, 16, i, min(16, height - i - bottom))
                i += 10
            }

            renderBackgroundRow(graphics, width, 32 - bottom, height - bottom, bottom)
        }

        var textX: Int = MARGIN
        if (!stack.isEmpty) {
            textX += MARGIN + IMAGE_SIZE
            graphics.renderFakeItem(stack, MARGIN, MARGIN + height() / 2 - IMAGE_SIZE)
        }

        graphics.drawString(component.getMinecraft().font, title, textX, MARGIN, -0xafffb0, false)
        for (i in message.indices) {
            graphics.drawString(
                component.getMinecraft().font,
                message[i],
                textX,
                LINE_SPACING + (i + 1) * LINE_SPACING,
                -0x1000000,
                false,
            )
        }

        return if (time - firstDisplay < DISPLAY_TIME) Toast.Visibility.SHOW else Toast.Visibility.HIDE
    }

    companion object {
        val TRANSFER_NO_RESPONSE_TOKEN: Any = Any()

        private const val DISPLAY_TIME = 7000L
        private const val MAX_LINE_SIZE = 200

        private const val IMAGE_SIZE = 16
        private const val LINE_SPACING = 10
        private const val MARGIN = 8

        private fun renderBackgroundRow(
            graphics: GuiGraphics,
            x: Int,
            u: Int,
            y: Int,
            height: Int,
        ) {
            val leftOffset = 5
            val rightOffset = min(60, x - leftOffset)

            graphics.blit(Toast.TEXTURE, 0, y, 0, 32 + u, leftOffset, height)
            var k = leftOffset
            while (k < x - rightOffset) {
                graphics.blit(Toast.TEXTURE, k, y, 32, 32 + u, min(64, x - k - rightOffset), height)
                k += 64
            }

            graphics.blit(Toast.TEXTURE, x - rightOffset, y, 160 - rightOffset, 32 + u, rightOffset, height)
        }
    }
}
