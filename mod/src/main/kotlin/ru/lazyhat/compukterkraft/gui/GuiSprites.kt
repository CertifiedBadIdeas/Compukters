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

import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.client.resources.TextureAtlasHolder
import net.minecraft.resources.ResourceLocation
import ru.lazyhat.compukterkraft.MOD_ID
import ru.lazyhat.compukterkraft.block.ComputerFamily
import ru.lazyhat.compukterkraft.utils.SingletonHolder

class GuiSprites(
    textureManager: TextureManager,
) : TextureAtlasHolder(textureManager, TEXTURE, SPRITE_SHEET) {
    companion object : SingletonHolder<GuiSprites>() {
        val SPRITE_SHEET: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui")
        val TEXTURE: ResourceLocation = SPRITE_SHEET.withPath { "textures/atlas/$it.png" }

        val TURNED_OFF = button("turned_off")
        val TURNED_ON = button("turned_on")
        val TERMINATE = button("terminate")

        val COMPUTER_ADVANCED = computer(name = "advanced", pocket = false, sidebar = true)

        fun button(name: String) =
            ButtonTextures(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui/sprites/buttons/$name"),
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui/sprites/buttons/${name}_hover"),
            )

        fun computer(
            name: String,
            pocket: Boolean,
            sidebar: Boolean,
        ) = ComputerTextures(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui/border_$name"),
            if (pocket) ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui/pocket_bottom_$name") else null,
            if (sidebar) ResourceLocation.fromNamespaceAndPath(MOD_ID, "gui/sidebar_$name") else null,
        )

        fun getComputerTextures(family: ComputerFamily) =
            when (family) {
                ComputerFamily.NORMAL -> TODO("Not implemented")
                ComputerFamily.ADVANCED -> COMPUTER_ADVANCED
                ComputerFamily.COMMAND -> TODO("Not implemented")
            }

        fun get(texture: ResourceLocation): TextureAtlasSprite = instance.getSprite(texture)

        fun initialize(textureManager: TextureManager) =
            GuiSprites(textureManager).also {
                instance = it
            }
    }

    data class ButtonTextures(
        val normal: ResourceLocation,
        val active: ResourceLocation,
    ) {
        fun get(active: Boolean): TextureAtlasSprite = get(if (active) this.active else normal)

        val textures = sequenceOf(normal, active)
    }

    data class ComputerTextures(
        val border: ResourceLocation,
        val pocketBottom: ResourceLocation?,
        val sidebar: ResourceLocation?,
    ) {
        val textures = sequenceOf(border, pocketBottom, sidebar).filterNotNull()
    }
}
