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
package ck.mod.gui

import ck.mod.gui.SpriteRenderer.Companion.u
import ck.mod.gui.SpriteRenderer.Companion.v
import net.minecraft.client.renderer.texture.TextureAtlasSprite

/**
 * Renders the borders of computers, either for a GUI ([dan200.computercraft.client.gui.ComputerScreen]) or
 * [in-hand pocket computers][PocketItemRenderer].
 */
object ComputerBorderRenderer {
    /**
     * The margin between the terminal and its border.
     */
    const val MARGIN: Int = 2

    /**
     * The width of the terminal border.
     */
    const val BORDER: Int = 12

    const val LIGHT_HEIGHT: Int = 8

    private const val TEX_SIZE = 36

    fun render(
        renderer: SpriteRenderer,
        textures: GuiSprites.ComputerTextures,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        withLight: Boolean,
    ) {
        val endX = x + width
        val endY = y + height

        val border = GuiSprites.get(textures.border)

        // Top bar
        blitBorder(renderer, border, x - BORDER, y - BORDER, 0, 0, BORDER, BORDER)
        blitBorder(renderer, border, x, y - BORDER, BORDER, 0, width, BORDER)
        blitBorder(renderer, border, endX, y - BORDER, BORDER * 2, 0, BORDER, BORDER)

        // Vertical bars
        blitBorder(renderer, border, x - BORDER, y, 0, BORDER, BORDER, height)
        blitBorder(renderer, border, endX, y, BORDER * 2, BORDER, BORDER, height)

        // Bottom bar. We allow for drawing a stretched version, which allows for additional elements (such as the
        // pocket computer's lights).
        if (withLight) {
            val pocketBottomTexture = textures.pocketBottom ?: throw NullPointerException("$textures has no pocket texture")
            val pocketBottom = GuiSprites.get(pocketBottomTexture)

            renderer.blitHorizontalSliced(
                pocketBottom,
                x - BORDER,
                endY,
                width + BORDER * 2,
                BORDER + LIGHT_HEIGHT,
                BORDER,
                BORDER,
                BORDER * 3,
            )
        } else {
            blitBorder(renderer, border, x - BORDER, endY, 0, BORDER * 2, BORDER, BORDER)
            blitBorder(renderer, border, x, endY, BORDER, BORDER * 2, width, BORDER)
            blitBorder(renderer, border, endX, endY, BORDER * 2, BORDER * 2, BORDER, BORDER)
        }
    }

    private fun blitBorder(
        renderer: SpriteRenderer,
        sprite: TextureAtlasSprite,
        x: Int,
        y: Int,
        u: Int,
        v: Int,
        width: Int,
        height: Int,
    ) {
        renderer.blit(
            x,
            y,
            width,
            height,
            u(sprite, u, TEX_SIZE),
            v(sprite, v, TEX_SIZE),
            u(sprite, u + BORDER, TEX_SIZE),
            v(sprite, v + BORDER, TEX_SIZE),
        )
    }
}
