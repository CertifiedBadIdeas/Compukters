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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class K16VmNativePackagingConventionTest {
    @Test
    fun productionUniversalJarBuildsWindowsK16NativeWithoutAffectingDevRuns() {
        val source = loomRunsConventionSource().readText()

        assertTrue(
            source.contains("buildK16VmWindowsX64NativeLibraryRelease"),
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
            source.contains("dependsOn(stageProductionK16VmNativeLibraries)"),
            "Production resource processing should depend on production native staging only for the production task path.",
        )
        assertTrue(
            source.contains("from(productionK16VmNativeResources)"),
            "Production jars should still include staged cross-platform native resources.",
        )
    }

    @Test
    fun gradleNativeBuildSurfaceUsesK16VmNamesWithoutRustVmAliases() {
        val source = loomRunsConventionSource().readText()

        assertTrue(source.contains("buildK16VmNativeLibrary"))
        assertTrue(source.contains("buildK16VmNativeLibraryRelease"))
        assertTrue(source.contains("prepareBundledK16VmNativeLibraries"))
        assertTrue(source.contains("stageProductionK16VmNativeLibraries"))
        assertTrue(source.contains("applyK16Vm"))
        assertFalse(source.contains("buildRustVm"))
        assertFalse(source.contains("prepareBundledRustVm"))
        assertFalse(source.contains("stageProductionRustVm"))
        assertFalse(source.contains("applyRustVm"))
        assertFalse(source.contains("productionRustVmNativeResources"))
    }

    @Test
    fun gradleNativeBuildUsesK16VmCrateDirectoryWithoutRuxVmPath() {
        val rootBuild = rootBuildSource().readText()
        val loomConvention = loomRunsConventionSource().readText()

        assertTrue(rootBuild.contains("native/k16-vm/src/generated/font_mono5x7.rs"))
        assertTrue(loomConvention.contains("dir(\"native/k16-vm\")"))
        assertFalse(rootBuild.contains("native/rux-vm"))
        assertFalse(loomConvention.contains("native/rux-vm"))
    }

    @Test
    fun gradleNativeRuntimePropertiesUseK16NamespaceWithoutRuxFallbacks() {
        val kotlinConvention = kotlinConventionSource().readText()
        val loomConvention = loomRunsConventionSource().readText()

        assertTrue(kotlinConvention.contains("k16.vm.native.library"))
        assertTrue(loomConvention.contains("k16.vm.native.display"))
        assertTrue(loomConvention.contains("k16.vm.native.daemon"))
        assertFalse(kotlinConvention.contains("rux.vm.native."))
        assertFalse(loomConvention.contains("rux.vm.native."))
    }

    @Test
    fun metadataGenerationDoesNotKeepLegacyRuxImages() {
        val source = metadataConventionSource().readText()

        assertFalse(source.contains(".ruxi"))
        assertFalse(source.contains(".k16i"))
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

    private fun rootBuildSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "..", "build.gradle.kts").normalize(),
                Path.of(System.getProperty("user.dir"), "build.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate root build.gradle.kts from ${System.getProperty("user.dir")}")
    }

    private fun kotlinConventionSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "src/main/kotlin/kotlin-convention.gradle.kts"),
                Path.of(System.getProperty("user.dir"), "build-scripts/src/main/kotlin/kotlin-convention.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate kotlin-convention.gradle.kts from ${System.getProperty("user.dir")}")
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
