/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.impl.terminal.fontDescription
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.display.DisplayTextLayout

class DisplayBlockEntityRenderer(
    context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<NeoForgeDisplayBlockEntity, DisplayRenderState> {
    private val profile = TerminalFontProfile.DINA

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
        state.rows = entity.displayRows()
    }

    override fun submit(
        state: DisplayRenderState,
        pose: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.rows.all(String::isBlank)) return
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()))
        pose.translate(0.0, 0.0, 0.503)
        pose.scale(SCALE, -SCALE, SCALE)
        DisplayTextLayout.forEachGlyph(state.rows, profile) { x, y, codePoint ->
            val text =
                Component.literal(String(Character.toChars(codePoint))).withStyle { style -> style.withFont(profile.fontDescription) }
            collector.submitText(
                pose,
                x.toFloat(),
                y.toFloat(),
                text.visualOrderText,
                false,
                Font.DisplayMode.POLYGON_OFFSET,
                FULL_BRIGHT,
                TEXT_COLOR,
                0,
                0,
            )
        }
        pose.popPose()
    }

    private companion object {
        const val SCALE = 0.0065f
        const val FULL_BRIGHT = 0xF000F0
        val TEXT_COLOR = 0xFF9FE8C3.toInt()
    }
}

class DisplayRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var rows: List<String> = emptyList()
}
