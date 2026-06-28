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

class K16FirmwareVolumeBuildScriptTest {
    @Test
    fun systemStorage0TaskCreatesPartitionedVolumeBeforePutBoot() {
        val buildScript =
            Path.of("..", "modules", "v1_21_1", "v1_21_1-neoforge", "build.gradle.kts")
                .normalize()
                .readText()
        val taskBody =
            buildScript.substringAfter("val createK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Boot =")

        assertTrue(taskBody.contains("\"volume\""), "storage0 task should invoke k16 volume tooling")
        assertTrue(taskBody.contains("\"init\""), "storage0 task must create a K16PT partitioned volume")
        assertFalse(taskBody.contains("\"create\""), "plain k16 volume create is not accepted by put-boot")
        assertFalse(buildScript.contains("createRuxSystemStorage0"))
        assertFalse(buildScript.contains("putRuxSystemStorage0Boot"))
    }

    @Test
    fun systemStorage0StagesUseDistinctGradleOutputs() {
        val buildScript =
            Path.of("..", "modules", "v1_21_1", "v1_21_1-neoforge", "build.gradle.kts")
                .normalize()
                .readText()
        val createTask =
            buildScript.substringAfter("val createK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Boot =")
        val putBootTask =
            buildScript.substringAfter("val putK16SystemStorage0Boot =")
                .substringBefore("val compileK16SystemStorage0 =")
        val putKernelTask =
            buildScript.substringAfter("val compileK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Init =")
        val putInitTask =
            buildScript.substringAfter("val putK16SystemStorage0Init =")
                .substringBefore("sourceSets.main")

        assertTrue(buildScript.contains("val k16EmptyStorage0Artifact"))
        assertTrue(buildScript.contains("val k16BootStorage0Artifact"))
        assertTrue(buildScript.contains("val k16KernelStorage0Artifact"))
        assertTrue(createTask.contains("outputs.file(k16EmptyStorage0Artifact)"))
        assertTrue(putBootTask.contains("inputs.file(k16EmptyStorage0Artifact)"))
        assertTrue(putBootTask.contains("outputs.file(k16BootStorage0Artifact)"))
        assertTrue(putKernelTask.contains("inputs.file(k16BootStorage0Artifact)"))
        assertTrue(putKernelTask.contains("outputs.file(k16KernelStorage0Artifact)"))
        assertTrue(putInitTask.contains("inputs.file(k16KernelStorage0Artifact)"))
        assertTrue(putInitTask.contains("outputs.file(k16SystemStorage0Resource)"))
        assertFalse(createTask.contains("outputs.file(k16SystemStorage0Resource)"))
        assertFalse(putKernelTask.contains("outputs.file(k16SystemStorage0Resource)"))
    }
}
