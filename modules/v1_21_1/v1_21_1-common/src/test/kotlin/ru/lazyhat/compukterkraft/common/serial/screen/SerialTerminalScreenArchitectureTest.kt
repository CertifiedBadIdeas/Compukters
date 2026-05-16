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

package ru.lazyhat.compukterkraft.common.serial.screen

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialTerminalScreenArchitectureTest {
    private val source =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/serial/screen/SerialTerminalScreen.kt")
            .readText()

    @Test
    fun serialTerminalKeepsInventoryKeyAsTextInputInsteadOfClosingScreen() {
        assertTrue(source.contains("keyInventory"))
        assertTrue(source.contains(".matches(keyCode, scanCode)"))
    }

    @Test
    fun serialTerminalScreenIsAuthoredWithTheUiDsl() {
        assertTrue(source.contains("DslContainerScreen<SerialTerminalMenu>"))
        assertTrue(source.contains("override fun content(): UiElement"))
        assertTrue(source.contains("ui("))
        assertTrue(source.contains("column("))
        assertTrue(source.contains("row("))
        assertTrue(source.contains("keySurface("))
        assertTrue(source.contains(".fillMaxWidth()"))
        assertFalse(source.contains(".offset("))
        assertFalse(source.contains("AbstractContainerScreen"))
    }

    @Test
    fun serialTerminalShowsLinkStateWithoutComputerPowerSemantics() {
        assertTrue(source.contains("gui.compukterkraft.serial_terminal.linked"))
        assertFalse(source.contains("linked_on"))
        assertFalse(source.contains("linked_off"))
        assertFalse(source.contains("menu.isComputerOn"))
    }

    @Test
    fun serialTerminalShowsRawRxTxByteCounters() {
        assertTrue(source.contains("rxBytes"))
        assertTrue(source.contains("txBytes"))
        assertTrue(source.contains("RX"))
        assertTrue(source.contains("TX"))
    }
}
