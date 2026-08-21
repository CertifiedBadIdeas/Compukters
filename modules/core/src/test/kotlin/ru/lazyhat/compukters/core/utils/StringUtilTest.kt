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
package ru.lazyhat.compukters.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class StringUtilTest {
    @Test
    fun unicodeToTerminalMapsAsciiAndLatin1Directly() {
        assertEquals('a'.code, StringUtil.unicodeToTerminal('a'.code))
        assertEquals(0xE9, StringUtil.unicodeToTerminal(0xE9))
    }

    @Test
    fun unicodeToTerminalMapsCraftOsSymbols() {
        assertEquals(3, StringUtil.unicodeToTerminal(0x2665))
        assertEquals(26, StringUtil.unicodeToTerminal(0x2192))
    }

    @Test
    fun unicodeToTerminalRejectsUnmappedUnicode() {
        assertEquals(-1, StringUtil.unicodeToTerminal(0x1F642))
    }
}
