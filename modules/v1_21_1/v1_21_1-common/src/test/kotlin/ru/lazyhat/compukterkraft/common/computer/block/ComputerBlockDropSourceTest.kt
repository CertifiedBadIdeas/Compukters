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
    fun abstractComputerBlockMatchesCcTweakedDropLifecycle() {
        val source = abstractComputerBlockSource().readText()

        assertTrue(
            source.contains("player.awardStat(Stats.BLOCK_MINED.get(this))"),
            "AbstractComputerBlock.playerDestroy should award the mined-block statistic directly.",
        )
        assertTrue(
            source.contains("player.causeFoodExhaustion(0.005f)"),
            "AbstractComputerBlock.playerDestroy should apply vanilla exhaustion directly.",
        )
        assertTrue(
            !source.contains("super.playerDestroy(level, player, pos, state, blockEntity, tool)"),
            "AbstractComputerBlock.playerDestroy should not delegate to the vanilla drop path when mirroring CC:Tweaked.",
        )
        assertTrue(
            source.contains("dropResources(state, level, pos, level.getBlockEntity(pos))"),
            "AbstractComputerBlock.playerWillDestroy should always trigger the server-side loot path from playerWillDestroy.",
        )
        assertTrue(
            !source.contains("player.abilities.instabuild"),
            "AbstractComputerBlock.playerWillDestroy should not special-case creative players when mirroring CC:Tweaked.",
        )
    }

    private fun abstractComputerBlockSource(): Path {
        val candidates =
            listOf(
                Path.of(
                    System.getProperty("user.dir"),
                    "src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlock.kt",
                ),
                Path.of(
                    System.getProperty("user.dir"),
                    "modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlock.kt",
                ),
            )

        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate AbstractComputerBlock.kt from test working directory ${System.getProperty("user.dir")}")
    }
}
