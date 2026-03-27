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
package ck.mod.gui

import ck.mod.MOD_ID
import ck.mod.gui.terminal.Terminal
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Handles rendering fixed width text and computer terminals.
 *
 *
 * This class has several modes of usage:
 *
 *  * [.drawString]: Drawing basic text without a terminal (such as for printouts). Unlike the other methods,
 * this accepts a lightmap coordinate as, unlike terminals, printed pages render fullbright.
 *  * [.drawTerminal]: Draw a terminal with a cursor. This is used by the various computer GUIs to render the
 * whole term.
 *
 *
 * **IMPORTANT: ** When making changes to this class, please check if you need to make the same changes to
 * [DirectFixedWidthFontRenderer].
 */
object FixedWidthFontRenderer {
    const val FULL_BRIGHT_LIGHTMAP: Int = (0xF shl 4) or (0xF shl 20)
    val FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/gui/term_font.png")

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

    fun toGreyscale(rgb: DoubleArray): Float = ((rgb[0] + rgb[1] + rgb[2]) / 3).toFloat()

    fun getColour(
        c: Char,
        def: Colour,
    ): Int = 15 - Terminal.getColour(c, def)

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

    private fun drawQuad(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        palette: Palette,
        colourIndex: Char,
        light: Int,
    ) {
        val colour =
            palette.getRenderColours(
                getColour(
                    colourIndex,
                    Colour.BLACK,
                ),
            )
        drawQuad(emitter, x, y, 0f, width, height, colour, light)
    }

    private fun drawBackground(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        backgroundColour: TextBuffer,
        palette: Palette,
        leftMarginSize: Float,
        rightMarginSize: Float,
        height: Float,
        light: Int,
    ) {
        if (leftMarginSize > 0) {
            drawQuad(emitter, x - leftMarginSize, y, leftMarginSize, height, palette, backgroundColour.charAt(0), light)
        }

        if (rightMarginSize > 0) {
            drawQuad(
                emitter,
                x + backgroundColour.length() * FONT_WIDTH,
                y,
                rightMarginSize,
                height,
                palette,
                backgroundColour.charAt(backgroundColour.length() - 1),
                light,
            )
        }

        // Batch together runs of identical background cells.
        var blockStart = 0
        var blockColour = '\u0000'
        for (i in 0..<backgroundColour.length()) {
            val colourIndex = backgroundColour.charAt(i)
            if (colourIndex == blockColour) continue

            if (blockColour != '\u0000') {
                drawQuad(
                    emitter,
                    x + blockStart * FONT_WIDTH,
                    y,
                    (FONT_WIDTH * (i - blockStart)).toFloat(),
                    height,
                    palette,
                    blockColour,
                    light,
                )
            }

            blockColour = colourIndex
            blockStart = i
        }

        if (blockColour != '\u0000') {
            drawQuad(
                emitter,
                x + blockStart * FONT_WIDTH,
                y,
                FONT_WIDTH.toFloat() * (backgroundColour.length() - blockStart),
                height,
                palette,
                blockColour,
                light,
            )
        }
    }

    fun drawString(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        text: TextBuffer,
        textColour: TextBuffer,
        palette: Palette,
        light: Int,
    ) {
        for (i in 0..<text.length()) {
            val colour =
                palette.getRenderColours(
                    getColour(
                        textColour.charAt(i),
                        Colour.BLACK,
                    ),
                )

            var index: Int = text.charAt(i).code
            if (index > 255) index = '?'.code
            drawChar(emitter, x + i * FONT_WIDTH, y, index, colour, light)
        }
    }

    fun drawTerminalForeground(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        terminal: Terminal,
    ) {
        val palette = terminal.palette
        val height = terminal.height

        // The main text
        for (i in 0..<height) {
            val rowY = y + FONT_HEIGHT * i
            drawString(
                emitter,
                x,
                rowY,
                terminal.getLine(i),
                terminal.getTextColourLine(i),
                palette,
                FULL_BRIGHT_LIGHTMAP,
            )
        }
    }

    fun drawTerminalBackground(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        terminal: Terminal,
        topMarginSize: Float,
        bottomMarginSize: Float,
        leftMarginSize: Float,
        rightMarginSize: Float,
    ) {
        val palette = terminal.palette
        val height = terminal.height

        // Top and bottom margins
        drawBackground(
            emitter,
            x,
            y - topMarginSize,
            terminal.getBackgroundColourLine(0),
            palette,
            leftMarginSize,
            rightMarginSize,
            topMarginSize,
            FULL_BRIGHT_LIGHTMAP,
        )

        drawBackground(
            emitter,
            x,
            y + height * FONT_HEIGHT,
            terminal.getBackgroundColourLine(height - 1),
            palette,
            leftMarginSize,
            rightMarginSize,
            bottomMarginSize,
            FULL_BRIGHT_LIGHTMAP,
        )

        // The main text
        for (i in 0..<height) {
            val rowY = y + FONT_HEIGHT * i
            drawBackground(
                emitter,
                x,
                rowY,
                terminal.getBackgroundColourLine(i),
                palette,
                leftMarginSize,
                rightMarginSize,
                FONT_HEIGHT.toFloat(),
                FULL_BRIGHT_LIGHTMAP,
            )
        }
    }

    fun isCursorVisible(terminal: Terminal): Boolean {
        if (!terminal.getCursorBlink()) return false

        val cursorX: Int = terminal.cursorX
        val cursorY: Int = terminal.cursorY
        return cursorX >= 0 && cursorX < terminal.width && cursorY >= 0 && cursorY < terminal.height
    }

    fun drawCursor(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        terminal: Terminal,
    ) {
        if (isCursorVisible(terminal) && FrameInfo.globalCursorBlink) {
            val colour = terminal.palette.getRenderColours(15 - terminal.getTextColour())
            drawChar(
                emitter,
                x + terminal.cursorX * FONT_WIDTH,
                y + terminal.cursorY * FONT_HEIGHT,
                '_'.code,
                colour,
                FULL_BRIGHT_LIGHTMAP,
            )
        }
    }

    fun drawTerminal(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        terminal: Terminal,
        topMarginSize: Float,
        bottomMarginSize: Float,
        leftMarginSize: Float,
        rightMarginSize: Float,
    ) {
        drawTerminalBackground(
            emitter,
            x,
            y,
            terminal,
            topMarginSize,
            bottomMarginSize,
            leftMarginSize,
            rightMarginSize,
        )

        // Render the foreground with a slight offset. By calling .translate() on the matrix itself, we're translating
        // in screen space, rather than in model/view space.
        // It's definitely not perfect, but better than z fighting!
        val transformBackup = Matrix4f(emitter.poseMatrix)
        emitter.poseMatrix.translate(Vector3f(0f, 0f, Z_OFFSET))

        drawTerminalForeground(emitter, x, y, terminal)
        drawCursor(emitter, x, y, terminal)

        emitter.poseMatrix.set(transformBackup)
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
            .vertex(poseMatrix, x1, y1, z)
            .color(r, g, b, a)
            .uv(u1, v1)
            .uv2(light)
            .endVertex()
        consumer
            .vertex(poseMatrix, x1, y2, z)
            .color(r, g, b, a)
            .uv(u1, v2)
            .uv2(light)
            .endVertex()
        consumer
            .vertex(poseMatrix, x2, y2, z)
            .color(r, g, b, a)
            .uv(u2, v2)
            .uv2(light)
            .endVertex()
        consumer
            .vertex(poseMatrix, x2, y1, z)
            .color(r, g, b, a)
            .uv(u2, v1)
            .uv2(light)
            .endVertex()
    }

    class QuadEmitter(
        val poseMatrix: Matrix4f,
        val consumer: VertexConsumer,
    )
}
