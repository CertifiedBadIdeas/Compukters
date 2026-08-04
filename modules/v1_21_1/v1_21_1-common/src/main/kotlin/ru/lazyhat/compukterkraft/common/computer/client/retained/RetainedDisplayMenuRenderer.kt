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

package ru.lazyhat.compukterkraft.common.computer.client.retained

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedDisplayGeometryCompiler
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import kotlin.math.floor
import kotlin.math.min

data class RetainedDisplayViewport(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
)

class RetainedDisplayMenuRenderer {
    private var activePose: PoseStack? = null
    private var activePresentation: MinecraftRetainedNativePresentation? = null
    private var lastBounds: TerminalRect? = null
    private var lastViewport: RetainedDisplayViewport? = null
    private val managedDraw = Runnable(::drawActive)
    private val batchSubmitter =
        MinecraftRetainedBatchSubmitter { target, translationX, translationY ->
            val pose = checkNotNull(activePose)
            pose.pushPose()
            try {
                pose.translate(translationX.toFloat(), translationY.toFloat(), 0f)
                target.draw(pose.last().pose(), RenderSystem.getProjectionMatrix())
            } finally {
                pose.popPose()
            }
        }

    fun draw(
        guiGraphics: GuiGraphics,
        bounds: TerminalRect,
        presentation: MinecraftRetainedNativePresentation,
    ) {
        check(activePose == null) { "Retained menu renderer cannot be entered recursively" }
        cachedViewport(bounds)
        activePose = guiGraphics.pose()
        activePresentation = presentation
        try {
            @Suppress("DEPRECATION")
            guiGraphics.drawManaged(managedDraw)
        } finally {
            activePresentation = null
            activePose = null
        }
    }

    private fun drawActive() {
        val pose = checkNotNull(activePose)
        val presentation = checkNotNull(activePresentation)
        val viewport = checkNotNull(lastViewport)
        pose.pushPose()
        try {
            pose.translate(viewport.x, viewport.y, 0f)
            pose.scale(viewport.scale, viewport.scale, 1f)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            presentation.submit(batchSubmitter)
        } finally {
            pose.popPose()
        }
    }

    private fun cachedViewport(bounds: TerminalRect): RetainedDisplayViewport {
        if (bounds != lastBounds) {
            lastBounds = bounds
            lastViewport = viewport(bounds)
        }
        return checkNotNull(lastViewport)
    }

    companion object {
        fun viewport(bounds: TerminalRect): RetainedDisplayViewport {
            require(bounds.width > 0 && bounds.height > 0)
            val fit =
                min(
                    bounds.width.toFloat() / RetainedDisplayGeometryCompiler.LOGICAL_WIDTH,
                    bounds.height.toFloat() / RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT,
                )
            val scale = if (fit >= 1f) floor(fit) else fit
            val width = RetainedDisplayGeometryCompiler.LOGICAL_WIDTH * scale
            val height = RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT * scale
            return RetainedDisplayViewport(
                x = bounds.x + (bounds.width - width) / 2f,
                y = bounds.y + (bounds.height - height) / 2f,
                width = width,
                height = height,
                scale = scale,
            )
        }
    }
}
