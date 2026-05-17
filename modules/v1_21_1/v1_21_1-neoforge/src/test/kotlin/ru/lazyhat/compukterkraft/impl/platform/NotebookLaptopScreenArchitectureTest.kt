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

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NotebookLaptopScreenArchitectureTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun clientRegistryRoutesNotebookComputerMenusToLaptopScreen() {
        val clientRegistry = root
            .resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
            )
            .readText()

        assertTrue(
            clientRegistry.contains("NotebookScreen") &&
                clientRegistry.contains("NotebookItem") &&
                clientRegistry.contains("container.displayStack.item is NotebookItem"),
            "Client registry should route notebook computer menus to NotebookScreen using the synced display stack.",
        )
    }
}
