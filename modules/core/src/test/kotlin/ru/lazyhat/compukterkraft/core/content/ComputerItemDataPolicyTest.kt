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

package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerItemDataPolicyTest {
    @Test
    fun preservesExistingIdentityWhenPresent() {
        val stored = ComputerItemData(computerId = 7, label = "alpha")
        var allocationCalls = 0
        val resolved = ComputerItemDataPolicy.resolvePlacedData(stored) {
            allocationCalls += 1
            99
        }

        assertEquals(stored, resolved)
        assertEquals(0, allocationCalls)
    }

    @Test
    fun allocatesMissingComputerIdDuringPlacement() {
        val resolved = ComputerItemDataPolicy.resolvePlacedData(
            ComputerItemData(computerId = null, label = "beta"),
        ) { 42 }

        assertEquals(42, resolved.computerId)
        assertEquals("beta", resolved.label)
    }
}