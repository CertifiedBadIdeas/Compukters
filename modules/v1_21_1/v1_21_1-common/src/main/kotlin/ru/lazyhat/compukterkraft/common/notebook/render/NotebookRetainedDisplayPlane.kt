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

package ru.lazyhat.compukterkraft.common.notebook.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix4f
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedBatchSubmitter
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedNativePresentation
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedDisplayGeometryCompiler

class NotebookRetainedDisplayPlane {
    private val baseModelView = Matrix4f()
    private val batchModelView = Matrix4f()
    private var activeModelView: Matrix4f? = null
    private val batchSubmitter =
        MinecraftRetainedBatchSubmitter { target, translationX, translationY ->
            batchModelView
                .set(checkNotNull(activeModelView))
                .translate(translationX.toFloat(), translationY.toFloat(), 0f)
            target.draw(batchModelView, RenderSystem.getProjectionMatrix())
        }

    fun draw(
        pose: PoseStack,
        presentation: MinecraftRetainedNativePresentation,
    ) {
        check(activeModelView == null) { "Retained notebook display plane cannot be entered recursively" }
        activeModelView = transformToScreenSurface(pose)
        try {
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableCull()
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            presentation.submit(batchSubmitter)
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            RenderSystem.disableBlend()
            RenderSystem.enableCull()
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            activeModelView = null
        }
    }

    private fun transformToScreenSurface(pose: PoseStack): Matrix4f =
        baseModelView
            .set(pose.last().pose())
            .translate(
                SCREEN_CUBE_PIVOT_X / MODEL_PIXELS_PER_BLOCK,
                SCREEN_CUBE_PIVOT_Y / MODEL_PIXELS_PER_BLOCK,
                SCREEN_CUBE_PIVOT_Z / MODEL_PIXELS_PER_BLOCK,
            ).rotateX(Math.toRadians(SCREEN_CUBE_ROTATION_DEGREES.toDouble()).toFloat())
            .translate(
                -SCREEN_CUBE_PIVOT_X / MODEL_PIXELS_PER_BLOCK,
                -SCREEN_CUBE_PIVOT_Y / MODEL_PIXELS_PER_BLOCK,
                -SCREEN_CUBE_PIVOT_Z / MODEL_PIXELS_PER_BLOCK,
            ).translate(
                PLANE_LEFT_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
                PLANE_TOP_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
                PLANE_SURFACE_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
            ).scale(
                PLANE_WIDTH_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK / RetainedDisplayGeometryCompiler.LOGICAL_WIDTH,
                -PLANE_HEIGHT_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK / RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT,
                1f,
            )

    private companion object {
        const val MODEL_PIXELS_PER_BLOCK = 16f
        const val SCREEN_CUBE_PIVOT_X = -7f
        const val SCREEN_CUBE_PIVOT_Y = 2f
        const val SCREEN_CUBE_PIVOT_Z = 4f
        const val SCREEN_CUBE_ROTATION_DEGREES = -90f
        const val PLANE_LEFT_MODEL_PIXELS = -20.5f
        const val PLANE_TOP_MODEL_PIXELS = 9.5f
        const val PLANE_SURFACE_MODEL_PIXELS = 3.99f
        const val PLANE_WIDTH_MODEL_PIXELS = 13f
        const val PLANE_HEIGHT_MODEL_PIXELS = 8f
    }
}
