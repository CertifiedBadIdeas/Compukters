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

class NotebookBlockModelResourceTest {
    @Test
    fun notebookModelIsSplitIntoAnimationReadyBaseAndLidParts() {
        val json =
            checkNotNull(
                javaClass.classLoader.getResource("assets/compukterkraft/models/block/notebook.json"),
            ).readText()

        listOf(
            "base_shell",
            "keyboard_deck",
            "keyboard_panel",
            "touchpad",
            "hinge_left",
            "hinge_right",
            "lid_panel",
            "screen_panel",
            "screen_bezel_top",
            "screen_bezel_bottom",
        ).forEach { partName ->
            assertTrue(
                json.contains(""""name": "$partName""""),
                "Notebook model should keep '$partName' as a named element for future lid animation work.",
            )
        }
        assertTrue(
            json.contains(""""display": "compukterkraft:block/computer_advanced/computer_advanced_top""""),
            "Notebook model should bind a dedicated display texture alias instead of painting the lid as plain casing.",
        )
    }
}
