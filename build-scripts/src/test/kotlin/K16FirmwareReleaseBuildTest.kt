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

class K16FirmwareReleaseBuildTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun productionBuildUsesReleaseFirmwareProfileWithoutDebugFallback() {
        val buildScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val rustBinArtifacts = root.resolve("build-scripts/src/main/kotlin/K16RustBinArtifacts.kt").readText()
        val docs = root.resolve("docs/toolchains/k16-firmware-release-builds.md").readText()

        assertTrue(buildScript.contains("val k16FirmwareProfile"))
        assertFalse(buildScript.contains("isProductionUniversalJarRequested"))
        assertTrue(buildScript.contains(".orElse(\"release\")"))
        assertTrue(buildScript.contains("fun k16CargoProfileArgs"))
        assertTrue(buildScript.contains("listOf(\"--release\")"))
        assertFalse(buildScript.contains("-Copt-level=0"))
        assertTrue(buildScript.contains("K16RustBinArtifacts.deleteOutputs"))
        assertTrue(buildScript.contains("K16RustBinArtifacts.copy"))
        assertTrue(rustBinArtifacts.contains("fun profileDir"))
        assertTrue(rustBinArtifacts.contains("fun find"))
        assertTrue(rustBinArtifacts.contains("targetDir.resolve(\"k16-unknown-kraftos/\$profile\")"))
        assertTrue(buildScript.contains("inputs.property(\"k16FirmwareProfile\", k16FirmwareProfile)"))

        assertFalse(buildScript.contains("val debugDir = targetDir.resolve(\"k16-unknown-kraftos/debug\")"))
        assertFalse(buildScript.contains("targetDir.resolve(\"k16-unknown-kraftos/debug/\$binName\")"))

        assertTrue(docs.contains("-Pk16FirmwareProfile=release"))
        assertTrue(docs.contains("release profile by default"))
        assertTrue(docs.contains("There is no debug artifact fallback."))
        assertTrue(docs.contains("no longer overrides"))
    }
}
