/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.impl.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NeoForgeClientRunConfigSourceTest {
    @Test
    fun neoforgeClientRunConfigDisablesCoroutineDebugProbes() {
        val source = neoforgeBuildFile().readText()

        assertTrue(
            source.contains("property(\"kotlinx.coroutines.debug\", \"off\")"),
            "NeoForge client run config should disable coroutine debug probes to keep debugger launches from crashing when screens create coroutines.",
        )
    }

    @Test
    fun neoforgeClientRuntimeIncludesReusableUiDslModule() {
        val source = neoforgeBuildFile().readText()

        assertTrue(
            source.contains("sourceSet(\"main\", project(projects.uiDsl.path))"),
            "NeoForge dev runtime should expose :ui-dsl classes through the main mod source set.",
        )
        assertTrue(
            source.contains("shadowBundle(project(path = projects.uiDsl.path"),
            "Production NeoForge jar should bundle :ui-dsl with the loader leaf.",
        )
    }

    private fun neoforgeBuildFile(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "build.gradle.kts"),
                Path.of(System.getProperty("user.dir"), "modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts"),
            )

        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate v1_21_1-neoforge/build.gradle.kts from test working directory ${System.getProperty("user.dir")}")
    }
}
