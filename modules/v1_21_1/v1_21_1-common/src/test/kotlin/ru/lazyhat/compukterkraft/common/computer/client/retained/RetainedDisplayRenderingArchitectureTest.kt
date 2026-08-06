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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedDisplayRenderingArchitectureTest {
    private val mainRoot = Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common")
    private val retainedRoot = mainRoot.resolve("computer/client/retained")

    @Test
    fun retainedRendererUsesMinecraftBuffersWithoutBackendSpecificOrFramebufferApis() {
        val sources = retainedSources()

        assertTrue(sources.contains("VertexBuffer"))
        assertTrue(sources.contains("BufferBuilder"))
        assertTrue(sources.contains("DynamicTexture"))
        listOf(
            "RenderTarget",
            "com.mojang.blaze3d.opengl",
            "org.lwjgl.opengl",
            "vulkan",
        ).forEach { forbidden ->
            assertFalse(sources.contains(forbidden), "Retained rendering must not depend on $forbidden")
        }
    }

    @Test
    fun retainedRendererDoesNotReplayInstancesThroughPerFrameGuiCalls() {
        val sources = retainedSources()

        assertTrue(sources.contains("drawManaged"))
        assertFalse(sources.contains("guiGraphics.blit"))
        assertFalse(sources.contains("guiGraphics.fill"))
        assertFalse(sources.contains("drawString("))
    }

    @Test
    fun directSubmissionReusesMatricesAndBalancesRenderState() {
        val menu = retainedRoot.resolve("RetainedDisplayMenuRenderer.kt").readText()
        val batches = retainedRoot.resolve("MinecraftRetainedBatchCache.kt").readText()
        val textures = retainedRoot.resolve("MinecraftRetainedTextureCache.kt").readText()

        assertFalse(menu.contains("pose.pushPose()"))
        assertTrue(menu.contains("private val batchModelView = Matrix4f()"))
        assertTrue(menu.contains(".set(checkNotNull(activeModelView))"))
        assertTrue(menu.contains("RenderSystem.enableBlend()"))
        assertTrue(menu.contains("RenderSystem.disableBlend()"))
        assertTrue(textures.contains("texture?.close() ?: image.close()"))
        assertTrue(
            batches.contains("finally {\n            VertexBuffer.unbind()"),
            "A failed direct VBO draw must not leak the binding",
        )
    }

    @Test
    fun productionScreenUsesTheRetainedRendererAfterIssue460HardCutover() {
        val displayScreen = mainRoot.resolve("computer/screen/ComputerDisplayScreen.kt").readText()

        assertTrue(displayScreen.contains("ClientRetainedDisplays.attachMenu"))
        assertTrue(displayScreen.contains("RetainedDisplayMenuRenderer"))
        assertFalse(displayScreen.contains("DisplayTextureCache"))
        assertFalse(displayScreen.contains("ClientDisplayBuffer"))
    }

    private fun retainedSources(): String =
        Files.walk(retainedRoot).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".kt") }
                .sorted()
                .map { it.readText() }
                .toList()
                .joinToString("\n")
        }
}
