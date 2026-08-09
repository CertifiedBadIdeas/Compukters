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
import kotlin.test.assertTrue

class ComputerLootTableResourceTest {
    @Test
    fun advancedNotebookLootTableMatchesCcTweakedConditions() {
        val json =
            checkNotNull(
                javaClass.classLoader.getResource("data/compukterkraft/loot_table/blocks/advanced_notebook.json"),
            ).readText()

        assertTrue(
            json.contains("\"name\": \"compukterkraft:computer\""),
            "Advanced Notebook loot table should keep the dynamic computer drop entry.",
        )
        assertTrue(
            json.contains("compukterkraft:block_named"),
            "Advanced computer loot table should drop a computer when the block entity carries a custom name.",
        )
        assertTrue(
            json.contains("compukterkraft:has_id"),
            "Advanced computer loot table should drop a computer when the block entity has a persisted computer id.",
        )
        assertTrue(
            json.contains("compukterkraft:player_creative"),
            "Advanced computer loot table should explicitly invert the creative-player condition like CC:Tweaked.",
        )
        assertTrue(
            json.contains("\"condition\": \"minecraft:inverted\""),
            "Advanced computer loot table should invert the creative-player condition instead of removing it.",
        )
    }
}
