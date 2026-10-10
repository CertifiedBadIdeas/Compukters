/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import ru.lazyhat.compukters.minecraft.display.DisplayBlock

class DisplayBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<NeoForgeDisplayBlockEntity> {
    override fun render(
        entity: NeoForgeDisplayBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val location = DisplayTextureCache.texture(entity) ?: return
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(-entity.blockState.getValue(DisplayBlock.FACING).toYRot()))
        pose.translate(0.0, 0.0, 0.503)
        val vertices = buffers.getBuffer(RenderType.entityTranslucentEmissive(location))
        val saved = pose.last()

        fun vertex(
            x: Float,
            y: Float,
            u: Float,
            v: Float,
        ) {
            vertices
                .addVertex(saved, x, y, 0f)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0)
                .setNormal(saved, 0f, 0f, 1f)
        }
        vertex(-0.5f, 0.5f, 0f, 0f)
        vertex(-0.5f, -0.5f, 0f, 1f)
        vertex(0.5f, -0.5f, 1f, 1f)
        vertex(0.5f, 0.5f, 1f, 0f)
        pose.popPose()
    }
}
