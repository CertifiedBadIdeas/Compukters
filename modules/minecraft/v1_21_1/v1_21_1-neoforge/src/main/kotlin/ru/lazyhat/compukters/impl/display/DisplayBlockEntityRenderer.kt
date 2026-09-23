/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.network.chat.Component
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.impl.terminal.fontDescription
import ru.lazyhat.compukters.minecraft.display.DisplayBlock

class DisplayBlockEntityRenderer(
    context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<NeoForgeDisplayBlockEntity> {
    private val font = context.font
    private val profile = TerminalFontProfile.DINA

    override fun render(
        entity: NeoForgeDisplayBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val rows = entity.displayRows()
        if (rows.all(String::isBlank)) return
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(-entity.blockState.getValue(DisplayBlock.FACING).toYRot()))
        pose.translate(0.0, 0.0, 0.503)
        pose.scale(SCALE, -SCALE, SCALE)
        rows.forEachIndexed { y, row ->
            if (row.isBlank()) return@forEachIndexed
            val text =
                Component.literal(profile.mapRow(row).trimEnd()).withStyle { style -> style.withFont(profile.fontDescription) }
            font.drawInBatch(
                text,
                -60f,
                -50f + y * 10f,
                TEXT_COLOR,
                false,
                pose.last().pose(),
                buffers,
                Font.DisplayMode.POLYGON_OFFSET,
                0,
                LightTexture.FULL_BRIGHT,
            )
        }
        pose.popPose()
    }

    private fun TerminalFontProfile.mapRow(row: String): String =
        buildString {
            row.codePoints().forEach { codePoint -> appendCodePoint(renderCodePoint(codePoint)) }
        }

    private companion object {
        const val SCALE = 0.0065f
        val TEXT_COLOR = 0xFF9FE8C3.toInt()
    }
}
