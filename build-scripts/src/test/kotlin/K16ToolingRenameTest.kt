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
import kotlin.io.path.exists
import kotlin.io.path.readText

class K16ToolingRenameTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun hostMachineToolingLivesUnderRustHostK16Tools() {
        assertTrue(root.resolve("rust/host/k16-tools/Cargo.toml").exists())
        assertFalse(root.resolve("native/k16-tools/Cargo.toml").exists())
        assertFalse(root.resolve("native/rux-compiler/Cargo.toml").exists())

        val manifest = root.resolve("rust/host/k16-tools/Cargo.toml").readText()
        assertTrue(manifest.contains("name = \"k16-tools\""))
        assertFalse(manifest.contains("name = \"rux-compiler\""))
    }

    @Test
    fun guestRustCratesLiveUnderRustGuest() {
        assertTrue(root.resolve("rust/guest/Cargo.toml").exists())
        assertTrue(root.resolve("rust/guest/k16-abi/Cargo.toml").exists())
        assertTrue(root.resolve("rust/guest/k16-rt/Cargo.toml").exists())
        assertFalse(root.resolve("guest/Cargo.toml").exists())
    }

    @Test
    fun rustBiosLivesAsGuestCrate() {
        val workspaceManifest = root.resolve("rust/guest/Cargo.toml").readText()
        val biosManifest = root.resolve("rust/guest/k16-bios/Cargo.toml")
        val biosSource = root.resolve("rust/guest/k16-bios/src/main.rs")

        assertTrue(workspaceManifest.contains("\"k16-bios\""))
        assertTrue(biosManifest.exists())
        assertTrue(biosSource.exists())

        val source = biosSource.readText()
        assertTrue(source.contains("#![no_std]"))
        assertTrue(source.contains("#![no_main]"))
        assertTrue(source.contains("extern \"C\" fn _start() -> !"))
        assertTrue(source.contains("K16 BIOS"))
        assertTrue(source.contains("No bootable device"))
    }

    @Test
    fun neoforgeFirmwareBuildDoesNotUseRuxCompilerOrDeletedGuestExamples() {
        val buildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()

        assertTrue(buildScript.contains("rust/guest/k16-bios"))
        assertFalse(buildScript.contains("--bin\",\\n            \"rux\""))
        assertFalse(buildScript.contains("ruxCompilerManifest"))
        assertFalse(buildScript.contains("k16_bios.rx"))
        assertFalse(buildScript.contains("kernel_loader.rx"))
        assertFalse(buildScript.contains("display_ok.rx"))
        assertFalse(buildScript.contains("rust/host/k16-tools/examples"))
    }

    @Test
    fun activeAbiDocsUseK16FormatNames() {
        val abiDir = root.resolve("docs/abi")

        for (expected in
            listOf(
                "k16-cpu-v1.md",
                "k16-object-v1.md",
                "k16e-v1.md",
                "k16fs-v1.md",
                "k16-storage-volume-v1.md",
                "k16-computer-profile-v1.md",
                "k16-computer-snapshot-v1.md",
                "k16-machine-profile-v2.md",
            )
        ) {
            assertTrue(abiDir.resolve(expected).exists(), "missing ABI doc $expected")
        }

        for (retired in
            listOf(
                "rux16-v1.md",
                "rux16-object-v1.md",
                "ruxe-v1.md",
                "ruxfs-v1.md",
                "rux-storage-volume-v1.md",
                "rux-computer-profile-v1.md",
                "rux-computer-snapshot-v1.md",
                "rux-machine-profile-v2.md",
            )
        ) {
            assertFalse(abiDir.resolve(retired).exists(), "retired ABI doc still exists: $retired")
        }
    }

    @Test
    fun bundledLegacyRuxiResourcesAreRemoved() {
        val firmwareDir = root.resolve("modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware")

        assertFalse(firmwareDir.resolve("rux-bios.ruxi").exists())
        assertFalse(firmwareDir.resolve("rux-laptop.ruxi").exists())
        assertFalse(firmwareDir.resolve("rux-echo-live.ruxi").exists())
        assertFalse(firmwareDir.resolve("rux-terminal.ruxi").exists())
    }
}
