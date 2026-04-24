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

package ru.lazyhat.compukterkraft.lang.runtime

/**
 * Byte-stream I/O between the VM and attached terminals.
 *
 * Epic 1 exposes output only ([writeString]); input and an "attached terminal
 * count" signal are reserved for Epic 2 (network split).
 *
 * Data is a stream of VT-100-style bytes (UTF-16 chars at the Kotlin boundary).
 * In Epic 1 the server-side implementation pipes bytes through a VtParser into
 * the existing ScreenBuffer; later epics fan-out to multiple network sessions.
 */
interface ComputerStdioApi {
    fun writeString(text: String)
}
