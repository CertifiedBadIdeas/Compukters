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

package ru.lazyhat.compukters.impl.ui

import ru.lazyhat.compukters.impl.ide.IdeUiScale

internal class CompuktersUiViewport private constructor(
    val physicalScale: Int,
    val minecraftGuiScale: Int,
    val width: Int,
    val height: Int,
    val supported: Boolean,
) {
    val renderScale: Float = physicalScale.toFloat() / minecraftGuiScale.toFloat()
    private val inverseRenderScale: Double = minecraftGuiScale.toDouble() / physicalScale.toDouble()

    fun toVirtualX(x: Double): Double = x * inverseRenderScale

    fun toVirtualY(y: Double): Double = y * inverseRenderScale

    fun toVirtualDelta(delta: Double): Double = delta * inverseRenderScale

    fun toMinecraftX(x: Double): Double = x * renderScale.toDouble()

    fun toMinecraftY(y: Double): Double = y * renderScale.toDouble()

    companion object {
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 360

        fun admitIde(
            framebufferWidth: Int,
            framebufferHeight: Int,
            minecraftGuiScale: Int,
            mode: IdeUiScale = IdeUiScale.AUTO,
        ): CompuktersUiViewport =
            admit(
                framebufferWidth,
                framebufferHeight,
                minecraftGuiScale,
                fixedScale = mode.fixedScale ?: if (framebufferWidth >= 2560 && framebufferHeight >= 1440) 3 else 2,
            )

        fun admit(
            framebufferWidth: Int,
            framebufferHeight: Int,
            minecraftGuiScale: Int,
            fixedScale: Int? = null,
        ): CompuktersUiViewport {
            require(fixedScale == null || fixedScale > 0) { "UI fixed scale must be positive" }
            val safeWidth = framebufferWidth.coerceAtLeast(0)
            val safeHeight = framebufferHeight.coerceAtLeast(0)
            val safeMinecraftScale = minecraftGuiScale.coerceAtLeast(1)
            val physicalScale =
                fixedScale ?: minOf(
                    safeWidth / MIN_WIDTH,
                    safeHeight / MIN_HEIGHT,
                ).coerceAtLeast(1)
            return CompuktersUiViewport(
                physicalScale = physicalScale,
                minecraftGuiScale = safeMinecraftScale,
                width = safeWidth / physicalScale,
                height = safeHeight / physicalScale,
                supported = safeWidth / physicalScale >= MIN_WIDTH && safeHeight / physicalScale >= MIN_HEIGHT,
            )
        }
    }
}
