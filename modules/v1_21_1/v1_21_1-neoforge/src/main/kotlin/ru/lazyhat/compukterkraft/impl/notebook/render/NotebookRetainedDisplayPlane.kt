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

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedBatchSubmitter
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedNativePresentation
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedDisplayGeometryCompiler

class NotebookRetainedDisplayPlane {
    private var activePose: PoseStack? = null
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
        pose: PoseStack,
        presentation: MinecraftRetainedNativePresentation,
    ) {
        check(activePose == null) { "Retained notebook display plane cannot be entered recursively" }
        activePose = pose
        pose.pushPose()
        try {
            transformToScreenSurface(pose)
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableCull()
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            presentation.submit(batchSubmitter)
        } finally {
            RenderSystem.enableCull()
            pose.popPose()
            activePose = null
        }
    }

    private fun transformToScreenSurface(pose: PoseStack) {
        pose.translate(
            SCREEN_CUBE_PIVOT_X / MODEL_PIXELS_PER_BLOCK,
            SCREEN_CUBE_PIVOT_Y / MODEL_PIXELS_PER_BLOCK,
            SCREEN_CUBE_PIVOT_Z / MODEL_PIXELS_PER_BLOCK,
        )
        pose.mulPose(Axis.XP.rotationDegrees(SCREEN_CUBE_ROTATION_DEGREES))
        pose.translate(
            -SCREEN_CUBE_PIVOT_X / MODEL_PIXELS_PER_BLOCK,
            -SCREEN_CUBE_PIVOT_Y / MODEL_PIXELS_PER_BLOCK,
            -SCREEN_CUBE_PIVOT_Z / MODEL_PIXELS_PER_BLOCK,
        )
        pose.translate(
            PLANE_LEFT_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
            PLANE_TOP_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
            PLANE_SURFACE_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK,
        )
        pose.scale(
            PLANE_WIDTH_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK / RetainedDisplayGeometryCompiler.LOGICAL_WIDTH,
            -PLANE_HEIGHT_MODEL_PIXELS / MODEL_PIXELS_PER_BLOCK / RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT,
            1f,
        )
    }

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
