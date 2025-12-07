package ru.lazyhat.compuktercraft.gui

import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.client.resources.TextureAtlasHolder
import net.minecraft.resources.ResourceLocation
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.utils.SingletonHolder

class GuiSprites(
    textureManager: TextureManager,
) : TextureAtlasHolder(textureManager, TEXTURE, SPRITE_SHEET) {
    companion object : SingletonHolder<GuiSprites>() {
        val SPRITE_SHEET: ResourceLocation = ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui")
        val TEXTURE: ResourceLocation = SPRITE_SHEET.withPath { "textures/atlas/$it.png" }

        val TURNED_OFF = button("turned_off")
        val TURNED_ON = button("turned_on")
        val TERMINATE = button("terminate")

        val COMPUTER_ADVANCED = computer(name = "advanced", pocket = false, sidebar = true)

        fun button(name: String) =
            ButtonTextures(
                ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui/sprites/buttons/$name"),
                ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui/sprites/buttons/${name}_hover"),
            )

        fun computer(
            name: String,
            pocket: Boolean,
            sidebar: Boolean,
        ) = ComputerTextures(
            ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui/border_$name"),
            if (pocket) ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui/pocket_bottom_$name") else null,
            if (sidebar) ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "gui/sidebar_$name") else null,
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
