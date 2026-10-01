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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.font

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/** Only our explicit TTF draws opt in; vanilla text and its render types remain untouched. */
internal object JetBrainsMonoRendering {
    private const val MAX_RENDER_TYPES = 128
    private val renderTypes =
        object : LinkedHashMap<RenderType, RenderType>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RenderType, RenderType>): Boolean = size > MAX_RENDER_TYPES
        }

    fun buffers(delegate: MultiBufferSource): MultiBufferSource =
        MultiBufferSource { type -> delegate.getBuffer(renderTypes.getOrPut(type) { LinearFontRenderType(type) }) }

    fun drawString(
        graphics: GuiGraphics,
        font: Font,
        text: Component,
        x: Int,
        y: Int,
        color: Int,
        flush: Boolean = true,
    ) {
        font.drawInBatch(
            text.visualOrderText,
            x.toFloat(),
            y.toFloat(),
            color,
            false,
            graphics.pose().last().pose(),
            buffers(graphics.bufferSource()),
            Font.DisplayMode.NORMAL,
            0,
            LightTexture.FULL_BRIGHT,
        )
        // Match GuiGraphics' unmanaged draws: finish before another clip or UI operation changes state.
        if (flush) graphics.flush()
    }
}

private class LinearFontRenderType private constructor(
    delegate: RenderType,
    filter: LinearFontTextureFilter,
) : RenderType(
        "compukters_linear_font",
        delegate.format(),
        delegate.mode(),
        delegate.bufferSize(),
        delegate.affectsCrumbling(),
        delegate.sortOnUpload(),
        {
            delegate.setupRenderState()
            filter.apply()
        },
        {
            try {
                filter.restore()
            } finally {
                delegate.clearRenderState()
            }
        },
    ) {
    constructor(delegate: RenderType) : this(delegate, LinearFontTextureFilter())
}

/** Filtering belongs to the draw state, since vanilla reassigns NEAREST in each text setup. */
private class LinearFontTextureFilter {
    private var texture = 0
    private var minFilter = GL11.GL_NEAREST
    private var magFilter = GL11.GL_NEAREST

    fun apply() {
        RenderSystem.assertOnRenderThread()
        texture = RenderSystem.getShaderTexture(0)
        withTexture(texture) {
            minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER)
            magFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        }
    }

    fun restore() {
        withTexture(texture) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter)
        }
    }

    private inline fun withTexture(
        texture: Int,
        action: () -> Unit,
    ) {
        val activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        RenderSystem.activeTexture(GL13.GL_TEXTURE0)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            RenderSystem.bindTexture(texture)
            action()
        } finally {
            RenderSystem.bindTexture(previousTexture)
            RenderSystem.activeTexture(activeUnit)
        }
    }
}
