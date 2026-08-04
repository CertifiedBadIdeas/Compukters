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

package ru.lazyhat.compukterkraft.impl.notebook.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedNativePresentation
import ru.lazyhat.compukterkraft.impl.notebook.block.NeoForgeNotebookBlockEntity
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.renderer.GeoRenderer
import software.bernie.geckolib.renderer.layer.GeoRenderLayer

fun interface NotebookRetainedDisplayPresentationLookup {
    fun presentation(animatable: NeoForgeNotebookBlockEntity): MinecraftRetainedNativePresentation?
}

class NotebookRetainedDisplayLayer(
    renderer: GeoRenderer<NeoForgeNotebookBlockEntity>,
    private val presentationLookup: NotebookRetainedDisplayPresentationLookup,
    private val plane: NotebookRetainedDisplayPlane = NotebookRetainedDisplayPlane(),
) : GeoRenderLayer<NeoForgeNotebookBlockEntity>(renderer) {
    override fun renderForBone(
        poseStack: PoseStack,
        animatable: NeoForgeNotebookBlockEntity,
        bone: GeoBone,
        renderType: RenderType,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (bone.name != SCREEN_BONE) return
        val presentation = presentationLookup.presentation(animatable) ?: return
        check(bufferSource is MultiBufferSource.BufferSource) {
            "Retained notebook display requires Minecraft's buffered render source"
        }
        bufferSource.endBatch()
        try {
            plane.draw(poseStack, presentation)
        } finally {
            bufferSource.getBuffer(renderType)
        }
    }

    private companion object {
        const val SCREEN_BONE = "screen"
    }
}
