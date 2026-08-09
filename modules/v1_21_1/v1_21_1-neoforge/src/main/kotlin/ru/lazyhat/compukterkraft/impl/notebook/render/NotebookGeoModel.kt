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

@file:Suppress("OVERRIDE_DEPRECATION")

package ru.lazyhat.compukterkraft.impl.notebook.render

import net.minecraft.resources.ResourceLocation
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.impl.notebook.block.NeoForgeNotebookBlockEntity
import software.bernie.geckolib.model.GeoModel

private val NORMAL_NOTEBOOK_TEXTURE: ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/block/notebook/notebook.png")
private val ADVANCED_NOTEBOOK_TEXTURE: ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/block/notebook/advanced_notebook.png")

internal fun notebookTexture(family: DeviceFamily): ResourceLocation =
    when (family) {
        DeviceFamily.NORMAL -> NORMAL_NOTEBOOK_TEXTURE
        DeviceFamily.ADVANCED -> ADVANCED_NOTEBOOK_TEXTURE
        DeviceFamily.COMMAND -> error("unsupported Notebook render family: command")
    }

class NotebookGeoModel : GeoModel<NeoForgeNotebookBlockEntity>() {
    override fun getModelResource(animatable: NeoForgeNotebookBlockEntity): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "geo/notebook.geo.json")

    override fun getTextureResource(animatable: NeoForgeNotebookBlockEntity): ResourceLocation =
        notebookTexture(animatable.family)

    override fun getAnimationResource(animatable: NeoForgeNotebookBlockEntity): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "animations/notebook.animation.json")
}
