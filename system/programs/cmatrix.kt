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

package compukter.system

import compukter.concurrent.Tasks
import compukter.io.Stderr
import compukter.terminal.Terminal
import compukter.terminal.TerminalColor
import kotlin.math.Rect2i
import kotlin.math.Vector2i
import kotlin.random.Random

/** Terminal rain using bounded column state and incremental cell updates. */
fun main(args: Array<String>) {
    if (args.size > 1 || (args.size == 1 && args[0] == "--help")) {
        usage()
        return
    }
    val delay = if (args.size == 0) 2 else parseDelay(args[0])
    if (delay == 0) {
        usage()
        return
    }
    val size = Terminal.size
    val width = size.x
    val height = size.y
    val columns = (width + 1) / 2
    val random = Random(0x43A791)
    val heads = IntArray(columns) { -random.nextInt(height) }
    val lengths = IntArray(columns) { 4 + random.nextInt(height) }
    val speeds = IntArray(columns) { 1 + random.nextInt(3) }
    val glyphs = CharArray(columns) { random.glyph() }
    val moving = BooleanArray(columns)
    var frame = 0
    Terminal.setColors(TerminalColor.GREEN, TerminalColor.BLACK)
    Terminal.clear()
    Terminal.setCursorVisible(false)
    while (true) {
        // Turn previous heads green; cells behind them retain their glyphs.
        Terminal.setColors(TerminalColor.GREEN, TerminalColor.BLACK)
        for (column in 0 until columns) {
            moving[column] = (frame + column) % speeds[column] == 0
            if (moving[column]) {
                val head = heads[column]
                if (head >= 0 && head < height) Terminal.writeAt(Vector2i(column * 2, head), glyphs[column].toString())
                heads[column] = head + 1
                val tail = heads[column] - lengths[column]
                if (tail >= 0 && tail < height) Terminal.fill(Rect2i(Vector2i(column * 2, tail), Vector2i(1, 1)))
                if (heads[column] >= height + lengths[column]) {
                    heads[column] = -random.nextInt(height)
                    lengths[column] = 4 + random.nextInt(height)
                }
            }
        }
        // All bright heads share one palette change, avoiding a color request per cell.
        Terminal.setColors(TerminalColor.WHITE, TerminalColor.BLACK)
        for (column in 0 until columns) {
            val head = heads[column]
            if (moving[column] && head >= 0 && head < height) {
                glyphs[column] = random.glyph()
                Terminal.writeAt(Vector2i(column * 2, head), glyphs[column].toString())
            }
        }
        frame = (frame + 1) % 6
        Tasks.sleepTicks(delay)
    }
}

private fun parseDelay(text: String): Int {
    if (text.isEmpty() || text.length > 2) return 0
    var value = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character < '0' || character > '9') return 0
        value = value * 10 + character.code - '0'.code
        index++
    }
    return if (value in 1..20) value else 0
}

private fun usage() {
    Stderr.write("usage: cmatrix [delay 1..20 ticks] (default 2; Ctrl+C exits)\n")
}

/** Select a glyph with the same seeded source as the column animation. */
private fun Random.glyph(): Char {
    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz@#$%&*+-=<>"
    return alphabet[nextInt(alphabet.length)]
}
