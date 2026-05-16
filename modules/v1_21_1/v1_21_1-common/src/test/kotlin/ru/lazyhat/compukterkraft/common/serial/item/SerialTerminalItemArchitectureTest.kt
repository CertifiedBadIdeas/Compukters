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

package ru.lazyhat.compukterkraft.common.serial.item

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialTerminalItemArchitectureTest {
    private val source =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/serial/item/SerialTerminalItem.kt")
            .readText()

    @Test
    fun serialTerminalBindingBelongsToTheItemStackNotThePlayerSession() {
        assertTrue(source.contains("readSerialBinding"))
        assertTrue(source.contains("writeSerialBinding"))
        assertTrue(source.contains("ServerContext.deviceManager.get"))
        assertFalse(source.contains("TransientPairing"))
        assertFalse(source.contains("BlockPos"))
        assertFalse(source.contains("blockPos"))
        assertFalse(source.contains("DIMENSION_ID"))
        assertFalse(source.contains("distanceToSqr"))
    }

    @Test
    fun serialTerminalTooltipShowsLinkState() {
        assertTrue(source.contains("appendHoverText"))
        assertTrue(source.contains("gui.compukterkraft.tooltip.serial_terminal_linked_computer"))
        assertTrue(source.contains("gui.compukterkraft.tooltip.serial_terminal_link_prompt"))
    }
}
