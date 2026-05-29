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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class RuxFirmwareVolumeBuildScriptTest {
    @Test
    fun systemStorage0TaskCreatesPartitionedVolumeBeforePutBoot() {
        val buildScript =
            Path.of("..", "modules", "v1_21_1", "v1_21_1-neoforge", "build.gradle.kts")
                .normalize()
                .readText()
        val taskBody =
            buildScript.substringAfter("val createRux16SystemStorage0 =")
                .substringBefore("val putRux16SystemStorage0Boot =")

        assertTrue(taskBody.contains("\"volume\""), "storage0 task should invoke rux volume tooling")
        assertTrue(taskBody.contains("\"init\""), "storage0 task must create a RUXPT partitioned volume")
        assertFalse(taskBody.contains("\"create\""), "plain rux volume create is not accepted by put-boot")
    }
}
