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

class K16ProductDisconnectConventionTest {
    private val root = repositoryRoot()

    @Test
    fun neoForgeProductDoesNotApplyK16FirmwareOrRegisterComputers() {
        val build = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val mod =
            root.resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
            ).readText()

        assertFalse(build.contains("k16FirmwareConvention"))
        assertFalse(mod.contains("ModRegistry"))
        assertFalse(mod.contains("KraftOsArtifactManifest"))
        assertFalse(mod.contains("ModObjects"))
    }

    @Test
    fun loomDoesNotBuildStageOrConfigureK16Runtime() {
        val source = root.resolve("build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts").readText()

        assertFalse(source.contains("buildK16Vm"))
        assertFalse(source.contains("BundledK16Vm"))
        assertFalse(source.contains("ProductionK16Vm"))
        assertFalse(source.contains("applyK16Vm"))
        assertFalse(source.contains("k16.vm.native"))
        assertTrue(source.contains("buildProductionUniversalJar"))
        assertTrue(source.contains("dependsOn(tasks.named(\"remapJar\"))"))
    }

    @Test
    fun retainedNetworkRegistryContainsOnlyChatTable() {
        val source =
            root.resolve(
                "modules/v1_21_1/v1_21_1-common/src/main/kotlin/" +
                    "ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt",
            ).readText()

        assertTrue(source.contains("val CHAT_TABLE"))
        assertFalse(source.contains("COMPUTER_ACTION"))
        assertFalse(source.contains("KEY_EVENT"))
        assertFalse(source.contains("MOUSE_EVENT"))
        assertFalse(source.contains("PASTE_EVENT"))
        assertFalse(source.contains("RETAINED_DISPLAY_"))
    }

    private fun repositoryRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull {
                Files.exists(it.resolve("settings.gradle.kts")) &&
                    Files.exists(it.resolve("modules/v1_21_1/v1_21_1-neoforge"))
            }
            ?: error("Could not locate repository root from $start")
    }
}
