/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.font

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.gui.GlyphRenderState
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f

/** Explicit TTF draws use cached linear samplers, without changing vanilla fonts or GPU state. */
internal object JetBrainsMonoRendering {
    fun drawString(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: Component,
        x: Int,
        y: Int,
        color: Int,
    ) {
        val state =
            GuiTextRenderState(
                font,
                text.visualOrderText,
                Matrix3x2f(graphics.pose()),
                x,
                y,
                color,
                0,
                false,
                false,
                graphics.peekScissorStack(),
            )
        val prepared = state.ensurePrepared()
        val bounds = state.bounds() ?: return
        prepared.visit(
            object : Font.GlyphVisitor {
                override fun acceptGlyph(glyph: TextRenderable.Styled) = submit(glyph)

                override fun acceptEffect(effect: TextRenderable) = submit(effect)

                private fun submit(renderable: TextRenderable) {
                    graphics.submitGuiElementRenderState(
                        LinearGlyphRenderState(GlyphRenderState(state.pose, renderable, state.scissor), bounds),
                    )
                }
            },
        )
    }

    fun submitText(
        collector: SubmitNodeCollector,
        pose: PoseStack,
        font: Font,
        text: Component,
        x: Float,
        y: Float,
        color: Int,
        light: Int,
    ) {
        font.prepareText(text.visualOrderText, x, y, color, false, false, 0).visit(
            object : Font.GlyphVisitor {
                override fun acceptGlyph(glyph: TextRenderable.Styled) = submit(glyph)

                override fun acceptEffect(effect: TextRenderable) = submit(effect)

                private fun submit(renderable: TextRenderable) {
                    // NeoForge supplies the same text pipeline with a linear sampler for world draws.
                    collector.submitCustomGeometry(
                        pose,
                        renderable.renderType(Font.DisplayMode.POLYGON_OFFSET, true),
                    ) { savedPose, vertices ->
                        renderable.render(savedPose.pose(), vertices, light, false)
                    }
                }
            },
        )
    }
}

internal class LinearGlyphRenderState(
    private val glyph: GlyphRenderState,
    private val bounds: ScreenRectangle,
) : GuiElementRenderState by glyph {
    override fun bounds(): ScreenRectangle = bounds

    override fun textureSetup(): TextureSetup =
        TextureSetup.singleTextureWithLightmap(
            glyph.renderable().textureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
}
