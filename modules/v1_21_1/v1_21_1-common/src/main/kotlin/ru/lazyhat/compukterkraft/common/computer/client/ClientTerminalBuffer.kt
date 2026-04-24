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
package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBufferVtSink
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser

/**
 * Client-side terminal buffer. Owns a [ScreenBuffer] sized from the local UI
 * and a [VtParser] that feeds stdout bytes into it.
 *
 * Dimensions come from the Minecraft GUI on screen init (Epic 2). If the
 * window resizes, call [resizeTo] — this drops the old buffer and returns a
 * new one; the server should replay the scrollback through
 * [ResizeTerminalServerMessage][ru.lazyhat.compukterkraft.common.computer.network.server.ResizeTerminalServerMessage].
 */
class ClientTerminalBuffer(
    cols: Int,
    rows: Int,
    color: Boolean = true,
) {
    private var buffer: ScreenBuffer = ScreenBuffer(cols, rows, color)
    private var parser: VtParser = VtParser(ScreenBufferVtSink(buffer))

    /**
     * Becomes `true` after the first non-empty byte chunk has been fed into
     * this buffer. The terminal screen uses this flag to distinguish the
     * "Connecting" state (buffer attached, server hasn't flushed yet) from
     * the "Active" state (at least one frame rendered).
     */
    var hasReceivedBytes: Boolean = false
        private set

    val cols: Int get() = buffer.width
    val rows: Int get() = buffer.height

    fun applyStdoutBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        hasReceivedBytes = true
        parser.feed(String(bytes, Charsets.UTF_8))
    }

    fun snapshot(): ScreenBufferSnapshot = buffer.forceSnapshot()

    fun resizeTo(
        newCols: Int,
        newRows: Int,
        color: Boolean = true,
    ) {
        if (newCols == buffer.width && newRows == buffer.height) return
        buffer = ScreenBuffer(newCols, newRows, color)
        parser = VtParser(ScreenBufferVtSink(buffer))
    }
}
