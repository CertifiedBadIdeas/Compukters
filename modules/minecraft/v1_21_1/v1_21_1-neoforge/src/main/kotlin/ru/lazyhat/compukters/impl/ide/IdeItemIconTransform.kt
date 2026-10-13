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

internal inline fun <T> withIdeItemIconTransform(
    pose: PoseStack,
    bounds: IdeRect,
    draw: () -> T,
): T {
    pose.pushPose()
    try {
        pose.translate(bounds.left.toFloat(), bounds.top.toFloat(), 0f)
        val scale = minOf(bounds.width, bounds.height) / 16f
        // Keep the model inside its shared UI layer and cancel GuiGraphics' item depth bias.
        pose.scale(scale, scale, 0.01f)
        pose.translate(0f, 0f, -150f)
        return draw()
    } finally {
        pose.popPose()
    }
}
