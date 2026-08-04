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

package ru.lazyhat.compukterkraft.impl.platform

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedNotebookDisplayArchitectureTest {
    private val root =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve("gradle/libs.versions.toml").exists() }
    private val renderRoot =
        root.resolve(
            "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                "ru/lazyhat/compukterkraft/impl/notebook/render",
        )
    private val commonPlane =
        root.resolve(
            "modules/v1_21_1/v1_21_1-common/src/main/kotlin/" +
                "ru/lazyhat/compukterkraft/common/notebook/render/NotebookRetainedDisplayPlane.kt",
        )

    @Test
    fun rendererComposesAnInactiveRetainedLayerUntilTheHardCutover() {
        val renderer = renderRoot.resolve("NotebookBlockEntityRenderer.kt").readText()
        val layer = renderRoot.resolve("NotebookRetainedDisplayLayer.kt").readText()

        assertTrue(renderer.contains("addRenderLayer(NotebookRetainedDisplayLayer"))
        assertTrue(renderer.contains("{ null }"), "Issue #459 must not activate a production observer lookup")
        assertFalse(renderer.contains("RetainedDisplayClientRegistry"))
        assertTrue(layer.contains("GeoRenderLayer<NeoForgeNotebookBlockEntity>"))
        assertTrue(layer.contains("renderForBone"))
        assertTrue(layer.contains("bone.name != SCREEN_BONE"))
        assertTrue(layer.contains("bufferSource.endBatch()"), "Direct cached VBOs require queued model geometry to flush first")
        assertTrue(layer.contains("bufferSource.getBuffer(renderType)"), "GeckoLib's active buffer must be restored after the layer")
    }

    @Test
    fun displayPlaneIsMinecraftNativeDepthTestedAndIndependentOfGeckoLib() {
        val plane = commonPlane.readText()
        val batchCache =
            root
                .resolve(
                    "modules/v1_21_1/v1_21_1-common/src/main/kotlin/" +
                        "ru/lazyhat/compukterkraft/common/computer/client/retained/MinecraftRetainedBatchCache.kt",
                ).readText()

        assertTrue(plane.contains("RenderSystem.enableDepthTest()"))
        assertTrue(plane.contains("SCREEN_CUBE_ROTATION_DEGREES = -90f"))
        assertTrue(plane.contains("PLANE_WIDTH_MODEL_PIXELS = 13f"))
        assertTrue(plane.contains("PLANE_HEIGHT_MODEL_PIXELS = 8f"))
        assertFalse(plane.contains("pose.pushPose()"))
        assertTrue(plane.contains("private val batchModelView = Matrix4f()"))
        assertTrue(plane.contains(".set(checkNotNull(activeModelView))"))
        assertTrue(plane.contains("RenderSystem.disableBlend()"))
        assertTrue(plane.contains("RenderSystem.enableCull()"))
        assertFalse(plane.contains("software.bernie.geckolib"))
        assertTrue(batchCache.contains("WORLD_EMISSIVE"))
        assertTrue(batchCache.contains("LightTexture.FULL_BRIGHT"))
        assertForbiddenRendererDependencies(plane + layerSource())
    }

    private fun layerSource(): String = renderRoot.resolve("NotebookRetainedDisplayLayer.kt").readText()

    private fun assertForbiddenRendererDependencies(source: String) {
        listOf(
            "RenderTarget",
            "com.mojang.blaze3d.opengl",
            "org.lwjgl.opengl",
            "vulkan",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "Retained notebook rendering must not depend on $forbidden")
        }
    }
}
