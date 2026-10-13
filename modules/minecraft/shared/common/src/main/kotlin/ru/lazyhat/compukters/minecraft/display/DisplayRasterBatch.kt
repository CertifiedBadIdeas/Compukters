/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import ru.lazyhat.compukters.core.display.DisplayRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned bounded raster wire format. Decode/validate the entire packet before touching a canvas. */
internal object DisplayRasterBatch {
    data class Command(
        val operation: Int,
        val fields: IntArray,
        val text: String = "",
        val colors: IntArray = intArrayOf(),
    ) {
        fun apply(
            canvas: DisplayCanvas,
            owner: Any,
        ) {
            fun clip(start: Int) = DisplayRegion(fields[start], fields[start + 1], fields[start + 2], fields[start + 3])
            when (operation) {
                8 -> canvas.fill(owner, 0, 0, canvas.activeWidth(owner), canvas.activeHeight(owner), fields[0])
                9 -> canvas.pixel(owner, fields[0], fields[1], fields[2])
                10 -> canvas.line(owner, fields[0], fields[1], fields[2], fields[3], fields[4])
                11 -> canvas.fill(owner, fields[0], fields[1], fields[2], fields[3], fields[4])
                12 -> DisplayRasterFont.draw(canvas, owner, fields[0], fields[1], text, fields[2], fields[3])
                13 -> canvas.imageRow(owner, fields[0], fields[1], colors)
                19 -> canvas.line(owner, fields[0], fields[1], fields[2], fields[3], fields[4], clip(5))
                20 -> DisplayRasterFont.draw(canvas, owner, fields[0], fields[1], text, fields[2], fields[3], clip(4))
            }
        }
    }

    fun decode(bytes: ByteArray): List<Command> {
        require(bytes.size in 8..4096) { "Invalid raster packet size" }
        val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun int(): Int {
            require(reader.remaining() >= 4) { "Truncated raster packet" }
            return reader.int
        }

        fun string(): String {
            val count = int()
            require(count in 0..512 && count <= reader.remaining() / 2) { "Invalid raster text size" }
            return String(CharArray(count) { reader.char })
        }
        require(int() == 1) { "Unsupported raster packet version" }
        val count = int()
        require(count in 1..128) { "Invalid raster command count" }
        val commands =
            List(count) {
                require(reader.hasRemaining()) { "Truncated raster packet" }
                val operation = reader.get().toInt() and 255
                val command =
                    when (operation) {
                        8 -> {
                            Command(operation, intArrayOf(int()))
                        }

                        9 -> {
                            Command(operation, IntArray(3) { int() })
                        }

                        10, 11 -> {
                            Command(operation, IntArray(5) { int() })
                        }

                        12, 20 -> {
                            val x = int()
                            val y = int()
                            val text = string()
                            val fields = IntArray(if (operation == 12) 4 else 8)
                            fields[0] = x
                            fields[1] = y
                            for (index in 2 until fields.size) fields[index] = int()
                            DisplayRasterFont.validate(text, fields[2], fields[3])
                            Command(operation, fields, text)
                        }

                        13 -> {
                            val x = int()
                            val y = int()
                            val pixels = int()
                            require(pixels in 1..256 && pixels <= reader.remaining() / 4) { "Invalid raster image row" }
                            Command(operation, intArrayOf(x, y), colors = IntArray(pixels) { int().also(DisplayCanvas::requireColor) })
                        }

                        19 -> {
                            Command(operation, IntArray(9) { int() })
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown raster command")
                        }
                    }
                val fields = command.fields
                when (operation) {
                    8 -> DisplayCanvas.requireColor(fields[0])
                    9 -> DisplayCanvas.requireColor(fields[2])
                    10, 11, 19 -> DisplayCanvas.requireColor(fields[4])
                }
                if (operation == 11) require(fields[2] >= 0 && fields[3] >= 0) { "Negative raster rectangle size" }
                if (operation == 19) DisplayRegion(fields[5], fields[6], fields[7], fields[8])
                if (operation == 20) DisplayRegion(fields[4], fields[5], fields[6], fields[7])
                command
            }
        require(!reader.hasRemaining()) { "Trailing raster packet bytes" }
        return commands
    }
}
