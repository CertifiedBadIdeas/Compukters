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

package ru.lazyhat.compukterkraft.impl

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16RuntimeProfilingArchitectureTest {
    @Test
    fun gradleExposesDedicatedK16WaitProfilingTask() {
        val rootBuildScript = Path.of("../../../build.gradle.kts").readText()
        val neoforgeBuildScript = Path.of("build.gradle.kts").readText()
        val docs = Path.of("../../../docs/PROFILING.md").readText()

        assertTrue(neoforgeBuildScript.contains("profileK16RuntimeWait"))
        assertTrue(neoforgeBuildScript.contains("K16RuntimeWaitProfilingTest"))
        assertTrue(neoforgeBuildScript.contains("showStandardStreams = true"))
        assertTrue(rootBuildScript.contains("profileK16RuntimeWait"))
        assertTrue(docs.contains("./gradlew-sandbox-dev --parallel profileK16RuntimeWait -Pk16BuildJobs=\$(nproc)"))
        assertTrue(docs.contains("k16Wait: entries="))
        assertFalse(docs.contains("profileRuntimeVmImage"))
    }

    @Test
    fun inGameFactoryKeepsRuntimeProfilingDisabledByDefault() {
        val source =
            Path
                .of("../v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt")
                .readText()

        assertFalse(source.contains("RecordingRuntimeMetricsCollector"))
    }
}
