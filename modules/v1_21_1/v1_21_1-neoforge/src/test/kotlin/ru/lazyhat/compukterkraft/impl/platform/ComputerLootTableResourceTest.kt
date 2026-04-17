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

package ru.lazyhat.compukterkraft.impl.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerLootTableResourceTest {
    @Test
    fun advancedComputerLootTableDoesNotSuppressCreativeDrops() {
        val json =
            checkNotNull(
                javaClass.classLoader.getResource("data/compukterkraft/loot_tables/blocks/computer_advanced.json"),
            ).readText()

        assertFalse(
            json.contains("compukterkraft:player_creative"),
            "Advanced computer loot table should not suppress drops for creative players.",
        )
        assertTrue(
            json.contains("\"name\": \"compukterkraft:computer\""),
            "Advanced computer loot table should keep the dynamic computer drop entry.",
        )
    }
}