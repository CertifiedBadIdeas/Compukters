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

package ru.lazyhat.compukters.impl.ide

import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdeItemIconTransformTest {
    @Test
    fun `native item stays inside toolbar layer below the project window`() {
        val pose = PoseStack()
        pose.translate(0f, 0f, 30f)
        val original = Matrix4f(pose.last().pose())
        withIdeItemIconTransform(pose, IdeRect(20, 40, 52, 72)) {
            // Reproduce the native GUI item transform, including its implicit depth offset.
            pose.translate(8f, 8f, 150f)
            pose.scale(16f, -16f, 16f)
            val center = pose.last().pose().transformPosition(0f, 0f, 0f, Vector3f())
            assertEquals(36f, center.x, 0.001f)
            assertEquals(56f, center.y, 0.001f)
            assertEquals(30f, center.z, 0.001f)
            for (modelDepth in listOf(-0.5f, 0.5f)) {
                val edge = pose.last().pose().transformPosition(0f, 0f, modelDepth, Vector3f())
                assertTrue(edge.z > 29f && edge.z < 31f)
                assertTrue(edge.z < 70f)
            }
        }
        assertEquals(original, pose.last().pose())
    }

    @Test
    fun `failed item rendering restores the GUI pose`() {
        val pose = PoseStack()
        val original = Matrix4f(pose.last().pose())
        assertFailsWith<IllegalStateException> {
            withIdeItemIconTransform(pose, IdeRect(20, 40, 36, 56)) { error("render failure") }
        }
        assertEquals(original, pose.last().pose())
    }
}
