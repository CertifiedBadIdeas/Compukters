/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.common.ui.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.lazyhat.compukters.common.asResource
import ru.lazyhat.compukters.core.gui.Colour
import ru.lazyhat.compukters.core.gui.FrameInfo
import ru.lazyhat.compukters.core.gui.Palette
import ru.lazyhat.compukters.lang.runtime.ScreenBufferSnapshot

/**
 * Renders fixed-width text and terminal grids from [ScreenBufferSnapshot].
 *
 * All rendering works with immutable snapshot data — no dependency on mutable Terminal objects.
 */
object FixedWidthFontRenderer {
    const val FULL_BRIGHT_LIGHTMAP: Int = (0xF shl 4) or (0xF shl 20)
    val FONT: ResourceLocation = "textures/gui/term_font.png".asResource()

    const val FONT_HEIGHT: Int = 9
    const val FONT_WIDTH: Int = 6
    const val WIDTH: Float = 256.0f

    val BACKGROUND_START: Float = (WIDTH - 6.0f) / WIDTH
    val BACKGROUND_END: Float = (WIDTH - 4.0f) / WIDTH

    private val BLACK: Int =
        FastColor.ARGB32.color(
            255,
            byteColour(Colour.BLACK.r).toInt(),
            byteColour(Colour.BLACK.g).toInt(),
            byteColour(Colour.BLACK.b).toInt(),
        )
    private const val Z_OFFSET = 1e-4f

    private fun byteColour(c: Float): Byte = (c * 255).toInt().toByte()

    private fun drawChar(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        index: Int,
        colour: Int,
        light: Int,
    ) {
        // Short circuit to avoid the common case - the texture should be blank here after all.
        if (index == '\u0000'.code || index == ' '.code) return

        val column = index % 16
        val row = index / 16

        val xStart = 1 + column * (FONT_WIDTH + 2)
        val yStart = 1 + row * (FONT_HEIGHT + 2)

        quad(
            emitter,
            x,
            y,
            x + FONT_WIDTH,
            y + FONT_HEIGHT,
            0f,
            colour,
            xStart / WIDTH,
            yStart / WIDTH,
            (xStart + FONT_WIDTH) / WIDTH,
            (yStart + FONT_HEIGHT) / WIDTH,
            light,
        )
    }

    fun drawQuad(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        z: Float,
        width: Float,
        height: Float,
        colour: Int,
        light: Int,
    ) {
        quad(emitter, x, y, x + width, y + height, z, colour, BACKGROUND_START, BACKGROUND_START, BACKGROUND_END, BACKGROUND_END, light)
    }

    /**
     * Draw a terminal from a [ScreenBufferSnapshot].
     */
    fun drawTerminal(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        snapshot: ScreenBufferSnapshot,
        topMarginSize: Float,
        bottomMarginSize: Float,
        leftMarginSize: Float,
        rightMarginSize: Float,
    ) {
        // Background
        drawTerminalBackground(emitter, x, y, snapshot, topMarginSize, bottomMarginSize, leftMarginSize, rightMarginSize)

        // Foreground with Z offset to avoid z-fighting
        val transformBackup = Matrix4f(emitter.poseMatrix)
        emitter.poseMatrix.translate(Vector3f(0f, 0f, Z_OFFSET))

        drawTerminalForeground(emitter, x, y, snapshot)
        drawCursor(emitter, x, y, snapshot)

        emitter.poseMatrix.set(transformBackup)
    }

    private fun drawTerminalForeground(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        snapshot: ScreenBufferSnapshot,
    ) {
        for (row in 0 until snapshot.height) {
            val rowY = y + FONT_HEIGHT * row
            for (col in 0 until snapshot.width) {
                var index = snapshot.charAt(col, row).code
                if (index > 255) index = '?'.code
                val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.fgAt(col, row))
                drawChar(emitter, x + col * FONT_WIDTH, rowY, index, colour, FULL_BRIGHT_LIGHTMAP)
            }
        }
    }

    private fun drawTerminalBackground(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        snapshot: ScreenBufferSnapshot,
        topMarginSize: Float,
        bottomMarginSize: Float,
        leftMarginSize: Float,
        rightMarginSize: Float,
    ) {
        // Top margin
        if (topMarginSize > 0) {
            val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.bgAt(0, 0))
            drawQuad(
                emitter,
                x - leftMarginSize,
                y - topMarginSize,
                0f,
                snapshot.width * FONT_WIDTH.toFloat() + leftMarginSize + rightMarginSize,
                topMarginSize,
                colour,
                FULL_BRIGHT_LIGHTMAP,
            )
        }
        // Bottom margin
        if (bottomMarginSize > 0) {
            val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.bgAt(0, snapshot.height - 1))
            drawQuad(
                emitter,
                x - leftMarginSize,
                y + snapshot.height * FONT_HEIGHT,
                0f,
                snapshot.width * FONT_WIDTH.toFloat() + leftMarginSize + rightMarginSize,
                bottomMarginSize,
                colour,
                FULL_BRIGHT_LIGHTMAP,
            )
        }

        // Row backgrounds
        for (row in 0 until snapshot.height) {
            val rowY = y + FONT_HEIGHT * row

            // Left margin
            if (leftMarginSize > 0) {
                val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.bgAt(0, row))
                drawQuad(
                    emitter,
                    x - leftMarginSize,
                    rowY,
                    0f,
                    leftMarginSize,
                    FONT_HEIGHT.toFloat(),
                    colour,
                    FULL_BRIGHT_LIGHTMAP,
                )
            }
            // Right margin
            if (rightMarginSize > 0) {
                val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.bgAt(snapshot.width - 1, row))
                drawQuad(
                    emitter,
                    x + snapshot.width * FONT_WIDTH,
                    rowY,
                    0f,
                    rightMarginSize,
                    FONT_HEIGHT.toFloat(),
                    colour,
                    FULL_BRIGHT_LIGHTMAP,
                )
            }

            // Cell backgrounds — batch contiguous runs of same colour
            var blockStart = 0
            var blockBg = snapshot.bgAt(0, row)
            for (col in 1 until snapshot.width) {
                val bg = snapshot.bgAt(col, row)
                if (bg != blockBg) {
                    val colour = Palette.DEFAULT.getRenderColours(15 - blockBg)
                    drawQuad(
                        emitter,
                        x + blockStart * FONT_WIDTH,
                        rowY,
                        0f,
                        ((col - blockStart) * FONT_WIDTH).toFloat(),
                        FONT_HEIGHT.toFloat(),
                        colour,
                        FULL_BRIGHT_LIGHTMAP,
                    )
                    blockStart = col
                    blockBg = bg
                }
            }
            val colour = Palette.DEFAULT.getRenderColours(15 - blockBg)
            drawQuad(
                emitter,
                x + blockStart * FONT_WIDTH,
                rowY,
                0f,
                ((snapshot.width - blockStart) * FONT_WIDTH).toFloat(),
                FONT_HEIGHT.toFloat(),
                colour,
                FULL_BRIGHT_LIGHTMAP,
            )
        }
    }

    private fun drawCursor(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        snapshot: ScreenBufferSnapshot,
    ) {
        if (!snapshot.cursorBlink || !FrameInfo.globalCursorBlink) return
        val cx = snapshot.cursorX
        val cy = snapshot.cursorY
        if (cx < 0 || cx >= snapshot.width || cy < 0 || cy >= snapshot.height) return
        val colour = Palette.DEFAULT.getRenderColours(15 - snapshot.currentFg)
        drawChar(emitter, x + cx * FONT_WIDTH, y + cy * FONT_HEIGHT, '_'.code, colour, FULL_BRIGHT_LIGHTMAP)
    }

    fun drawEmptyTerminal(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        drawQuad(emitter, x, y, 0f, width, height, BLACK, FULL_BRIGHT_LIGHTMAP)
    }

    fun toVertexConsumer(
        transform: PoseStack,
        consumer: VertexConsumer,
    ): QuadEmitter = QuadEmitter(transform.last().pose(), consumer)

    private fun quad(
        c: QuadEmitter,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
        colour: Int,
        u1: Float,
        v1: Float,
        u2: Float,
        v2: Float,
        light: Int,
    ) {
        val poseMatrix: Matrix4f = c.poseMatrix
        val consumer: VertexConsumer = c.consumer
        val r: Int = FastColor.ARGB32.red(colour)
        val g: Int = FastColor.ARGB32.green(colour)
        val b: Int = FastColor.ARGB32.blue(colour)
        val a: Int = FastColor.ARGB32.alpha(colour)

        consumer
            .addVertex(poseMatrix, x1, y1, z)
            .setColor(r, g, b, a)
            .setUv(u1, v1)
            .setLight(light)
        consumer
            .addVertex(poseMatrix, x1, y2, z)
            .setColor(r, g, b, a)
            .setUv(u1, v2)
            .setLight(light)
        consumer
            .addVertex(poseMatrix, x2, y2, z)
            .setColor(r, g, b, a)
            .setUv(u2, v2)
            .setLight(light)
        consumer
            .addVertex(poseMatrix, x2, y1, z)
            .setColor(r, g, b, a)
            .setUv(u2, v1)
            .setLight(light)
    }

    class QuadEmitter(
        val poseMatrix: Matrix4f,
        val consumer: VertexConsumer,
    )
}
