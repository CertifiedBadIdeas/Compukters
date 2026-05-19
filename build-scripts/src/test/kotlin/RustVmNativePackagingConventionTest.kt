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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class RustVmNativePackagingConventionTest {
    @Test
    fun productionUniversalJarBuildsWindowsNativeWithoutAffectingDevRuns() {
        val source = loomRunsConventionSource().readText()

        assertTrue(
            source.contains("buildRustVmWindowsX64NativeLibraryRelease"),
            "Production packaging should expose an explicit Windows x64 native build task.",
        )
        assertTrue(
            source.contains("x86_64-pc-windows-gnu"),
            "Windows x64 native builds should use the Rust GNU Windows target from Linux.",
        )
        assertTrue(
            source.contains("buildProductionUniversalJar"),
            "Production packaging should expose one task that stages cross-platform natives and builds the jar.",
        )
        assertTrue(
            source.contains("isProductionUniversalJarRequested"),
            "Production native resources must be gated to production builds so stale staged natives cannot shadow dev natives.",
        )
        assertTrue(
            source.contains("dependsOn(stageProductionRustVmNativeLibraries)"),
            "Production resource processing should depend on production native staging only for the production task path.",
        )
        assertTrue(
            source.contains("from(productionRustVmNativeResources)"),
            "Production jars should still include staged cross-platform native resources.",
        )
    }

    @Test
    fun metadataGenerationDoesNotTemplateExpandRuxImages() {
        val source = metadataConventionSource().readText()

        assertTrue(
            source.contains("endsWith(\".ruxi\")"),
            "Rux image binaries must be copied as binary resources, not parsed as Groovy templates.",
        )
    }

    private fun loomRunsConventionSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "src/main/kotlin/loom-runs-convention.gradle.kts"),
                Path.of(System.getProperty("user.dir"), "build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate loom-runs-convention.gradle.kts from ${System.getProperty("user.dir")}")
    }

    private fun metadataConventionSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "src/main/kotlin/metadata.gradle.kts"),
                Path.of(System.getProperty("user.dir"), "build-scripts/src/main/kotlin/metadata.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate metadata.gradle.kts from ${System.getProperty("user.dir")}")
    }
}
