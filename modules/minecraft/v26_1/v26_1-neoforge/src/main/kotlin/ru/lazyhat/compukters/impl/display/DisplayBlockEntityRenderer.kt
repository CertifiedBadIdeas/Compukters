/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.display.DisplayBlock

class DisplayBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<NeoForgeDisplayBlockEntity, DisplayRenderState> {
    override fun createRenderState(): DisplayRenderState = DisplayRenderState()

    override fun extractRenderState(
        entity: NeoForgeDisplayBlockEntity,
        state: DisplayRenderState,
        partialTick: Float,
        cameraPosition: Vec3,
        crumblingOverlay: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPosition, crumblingOverlay)
        state.facing = entity.blockState.getValue(DisplayBlock.FACING)
        state.texture = DisplayTextureCache.texture(entity)
    }

    override fun submit(
        state: DisplayRenderState,
        pose: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val location = state.texture ?: return
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()))
        pose.translate(0.0, 0.0, 0.503)
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(location)) { saved, vertices ->
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
        }
        pose.popPose()
    }
}

class DisplayRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var texture: Identifier? = null
}
