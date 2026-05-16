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
package ru.lazyhat.compukterkraft.common.serial

class SerialConsoleBuffer {
    private val history = mutableListOf<String>()
    private val input = StringBuilder()
    private var pendingOutput = StringBuilder()
    var rxBytes: Long = 0
        private set
    var txBytes: Long = 0
        private set

    val historyLines: List<String>
        get() = history.toList()

    val inputLine: String
        get() = input.toString()

    val pendingOutputLine: String
        get() = pendingOutput.toString()

    fun appendOutput(
        bytes: ByteArray,
        reset: Boolean = false,
    ) {
        if (reset) {
            history.clear()
            pendingOutput = StringBuilder()
            rxBytes = 0
        }
        rxBytes += bytes.size
        val text = bytes.decodeToString()
        for (ch in text) {
            when (ch) {
                '\r' -> Unit
                '\n' -> {
                    history += pendingOutput.toString()
                    pendingOutput = StringBuilder()
                }
                else -> pendingOutput.append(ch)
            }
        }
    }

    fun type(ch: Char) {
        if (!ch.isISOControl()) {
            input.append(ch)
        }
    }

    fun backspace() {
        if (input.isNotEmpty()) {
            input.deleteAt(input.lastIndex)
        }
    }

    fun submitLine(): ByteArray {
        val submitted = input.toString() + "\n"
        input.clear()
        val bytes = submitted.encodeToByteArray()
        txBytes += bytes.size
        return bytes
    }
}
