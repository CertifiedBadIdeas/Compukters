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

package ru.lazyhat.compukterkraft.common.computer.block

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ComputerBlockDropSourceTest {
    @Test
    fun abstractComputerBlockUsesVanillaDropPathAndCreativeSpecificManualDrop() {
        val source = abstractComputerBlockSource().readText()

        assertTrue(
            source.contains("super.playerDestroy(level, player, pos, state, blockEntity, tool)"),
            "AbstractComputerBlock.playerDestroy should delegate to the vanilla drop path.",
        )
        assertTrue(
            source.contains("player.abilities.instabuild"),
            "AbstractComputerBlock.playerWillDestroy should gate manual drops behind a creative-player check.",
        )
    }

    private fun abstractComputerBlockSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlock.kt"),
                Path.of(System.getProperty("user.dir"), "modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlock.kt"),
            )

        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate AbstractComputerBlock.kt from test working directory ${System.getProperty("user.dir")}")
    }
}