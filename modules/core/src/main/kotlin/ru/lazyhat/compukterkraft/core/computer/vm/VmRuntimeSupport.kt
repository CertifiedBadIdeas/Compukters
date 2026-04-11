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
package ru.lazyhat.compukterkraft.core.computer.vm

import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

// GLFW key constants (stable across all versions)
private const val KEY_ENTER = 257
private const val KEY_KP_ENTER = 335
private const val KEY_BACKSPACE = 259

internal class VmPathResolver(
    initialWorkingDirectory: String = "",
) {
    var workingDirectory: String = normalizeWorkingDirectory(initialWorkingDirectory)
        private set

    fun resolve(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == ".") return workingDirectory

        val segments = ArrayDeque<String>()
        val source =
            if (trimmed.startsWith('/')) {
                trimmed.removePrefix("/")
            } else {
                listOf(workingDirectory, trimmed).filter { it.isNotEmpty() }.joinToString("/")
            }

        source
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                when (segment) {
                    "." -> Unit
                    ".." -> segments.removeLastOrNull()
                    else -> segments.addLast(segment)
                }
            }

        return segments.joinToString("/")
    }

    fun updateWorkingDirectory(path: String) {
        workingDirectory = normalizeWorkingDirectory(path)
    }

    private fun normalizeWorkingDirectory(path: String): String = path.trim().trim('/')
}

internal object VmEventTextDecoder {
    fun typedText(event: VmEvent): String? {
        val bytes = event.arguments.firstOrNull() as? ByteArray ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    fun pastedText(event: VmEvent): String? {
        val buffer = event.arguments.firstOrNull() as? ByteBuffer ?: return null
        val copy = buffer.asReadOnlyBuffer()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }
}

internal class TerminalLineReader(
    private val receiveEvent: suspend () -> VmEvent,
    private val deferEvent: (VmEvent) -> Unit,
    private val write: suspend (String) -> Unit,
    private val printLine: suspend (String) -> Unit,
    private val setCursor: suspend (Int, Int) -> Unit,
    private val currentCursor: () -> Pair<Int, Int>,
    private val updateCursor: (Int, Int) -> Unit,
) {
    suspend fun readLine(prompt: String = ""): String {
        if (prompt.isNotEmpty()) {
            write(prompt)
        }

        val line = StringBuilder()
        val deferredEvents = ArrayDeque<VmEvent>()
        try {
            while (true) {
                val event = receiveEvent()
                when (event.name) {
                    "char" -> {
                        VmEventTextDecoder.typedText(event)?.let { chunk ->
                            line.append(chunk)
                            write(chunk)
                        }
                    }

                    "paste" -> {
                        VmEventTextDecoder.pastedText(event)?.let { chunk ->
                            line.append(chunk)
                            write(chunk)
                        }
                    }

                    "key" -> {
                        val keyCode = event.arguments.firstOrNull() as? Int ?: continue
                        when (keyCode) {
                            KEY_ENTER,
                            KEY_KP_ENTER,
                            -> {
                                printLine("")
                                return line.toString()
                            }

                            KEY_BACKSPACE -> {
                                if (line.isNotEmpty()) {
                                    line.deleteCharAt(line.lastIndex)
                                    val (cursorX, cursorY) = currentCursor()
                                    val rewound = (cursorX - 1).coerceAtLeast(0)
                                    updateCursor(rewound, cursorY)
                                    setCursor(rewound, cursorY)
                                    write(" ")
                                    updateCursor(rewound, cursorY)
                                    setCursor(rewound, cursorY)
                                }
                            }
                        }
                    }

                    else -> {
                        deferredEvents.addLast(event)
                    }
                }
            }
        } finally {
            while (deferredEvents.isNotEmpty()) {
                deferEvent(deferredEvents.removeFirst())
            }
        }
    }
}
