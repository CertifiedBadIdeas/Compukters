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
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class K16ToolingRenameTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun k16SourceLayoutUsesRoleBasedRootsWithoutLanguageBasedSourceRoots() {
        val requiredSourceRoots =
            listOf(
                "guest/firmware",
                "guest/platform/k16",
                "guest/kraftos",
                "guest/kraftos/abi",
                "guest/kraftos/kernel",
                "guest/kraftos/runtime",
                "guest/kraftos/libc",
                "guest/kraftos/lib/libkraft",
                "guest/kraftos/userland",
                "host/k16-tools",
                "host/k16-vm",
            )

        for (path in requiredSourceRoots) {
            assertTrue(root.resolve(path).isDirectory(), "K16 source root should exist: $path")
        }

        val retiredSourceRoots =
            listOf(
                "rust/guest",
                "rust/host",
                "guest/c",
                "guest/rust",
                "guest/runtime",
                "host/rust",
            )

        for (path in retiredSourceRoots) {
            assertFalse(root.resolve(path).exists(), "Retired K16 source root should not exist: $path")
        }
    }

    @Test
    fun hostMachineToolingLivesUnderRustHostK16Tools() {
        assertTrue(root.resolve("host/k16-tools/Cargo.toml").exists())
        assertFalse(root.resolve("native/k16-tools/Cargo.toml").exists())
        assertFalse(root.resolve("native/rux-compiler/Cargo.toml").exists())

        val manifest = root.resolve("host/k16-tools/Cargo.toml").readText()
        assertTrue(manifest.contains("name = \"k16-tools\""))
        assertFalse(manifest.contains("name = \"rux-compiler\""))
    }

    @Test
    fun guestRustCratesLiveUnderKraftOsTree() {
        val workspaceManifest = root.resolve("guest/kraftos/Cargo.toml").readText()
        val rustMemoryManifest = root.resolve("guest/kraftos/k16-memory/Cargo.toml")
        val rustMemorySource = root.resolve("guest/kraftos/k16-memory/src/lib.rs")
        val rtManifest = root.resolve("guest/kraftos/runtime/Cargo.toml").readText()

        assertTrue(root.resolve("guest/kraftos/Cargo.toml").exists())
        assertTrue(root.resolve("guest/kraftos/abi/Cargo.toml").exists())
        assertTrue(root.resolve("guest/kraftos/runtime/Cargo.toml").exists())
        assertFalse(workspaceManifest.contains("\"k16-memory\""))
        assertFalse(rustMemoryManifest.exists())
        assertFalse(rustMemorySource.exists())
        assertFalse(rtManifest.contains("k16-memory"))
        assertFalse(root.resolve("guest/Cargo.toml").exists())
    }

    @Test
    fun cBiosLivesOutsideRustGuestWorkspace() {
        val workspaceManifest = root.resolve("guest/kraftos/Cargo.toml").readText()
        val biosManifest = root.resolve("guest/kraftos/k16-bios/Cargo.toml")
        val rustBiosSource = root.resolve("guest/kraftos/k16-bios/src/main.rs")
        val cBiosSource = root.resolve("guest/firmware/bios/bios.c")

        assertFalse(workspaceManifest.contains("\"k16-bios\""))
        assertFalse(biosManifest.exists())
        assertFalse(rustBiosSource.exists())
        assertTrue(cBiosSource.exists())

        val source = cBiosSource.readText()
        assertTrue(source.contains("void _start(void)"))
        assertTrue(source.contains("print_bios_banner"))
        assertTrue(source.contains("print_no_bootable_device"))
        assertTrue(source.contains("load_k16e_from_storage0"))
    }

    @Test
    fun cBootloaderAndRustKernelUseSeparateSourcePaths() {
        val workspaceManifest = root.resolve("guest/kraftos/Cargo.toml").readText()
        val bootManifest = root.resolve("guest/kraftos/k16-boot/Cargo.toml")
        val rustBootSource = root.resolve("guest/kraftos/k16-boot/src/main.rs")
        val rustBootChainManifest = root.resolve("guest/kraftos/k16-boot-chain/Cargo.toml")
        val rustBootChainSource = root.resolve("guest/kraftos/k16-boot-chain/src/lib.rs")
        val rustImageManifest = root.resolve("guest/kraftos/k16-image/Cargo.toml")
        val rustImageSource = root.resolve("guest/kraftos/k16-image/src/lib.rs")
        val rustStorageManifest = root.resolve("guest/kraftos/k16-storage/Cargo.toml")
        val rustStorageSource = root.resolve("guest/kraftos/k16-storage/src/lib.rs")
        val cBootSource = root.resolve("guest/firmware/boot/boot.c")
        val cBootChainSource = root.resolve("guest/firmware/boot-chain/boot_chain.c")
        val cBootChainHeader = root.resolve("guest/firmware/boot-chain/boot_chain.h")
        val kernelManifest = root.resolve("guest/kraftos/kernel/Cargo.toml")
        val kernelSource = root.resolve("guest/kraftos/kernel/src/main.rs")
        val kernelBootChainSource = root.resolve("guest/kraftos/kernel/src/boot_chain.rs")
        val kernelImageSource = root.resolve("guest/kraftos/kernel/src/image.rs")
        val kernelKfsDeviceSource = root.resolve("guest/kraftos/kernel/src/kfs/device.rs")
        val kernelKfsBlockIoSource = root.resolve("guest/kraftos/kernel/src/kfs/block_io.rs")

        assertFalse(workspaceManifest.contains("\"k16-boot\""))
        assertFalse(workspaceManifest.contains("\"k16-boot-chain\""))
        assertFalse(workspaceManifest.contains("\"k16-image\""))
        assertFalse(workspaceManifest.contains("\"k16-storage\""))
        assertTrue(workspaceManifest.contains("\"kernel\""))
        assertFalse(bootManifest.exists())
        assertFalse(rustBootSource.exists())
        assertFalse(rustBootChainManifest.exists())
        assertFalse(rustBootChainSource.exists())
        assertFalse(rustImageManifest.exists())
        assertFalse(rustImageSource.exists())
        assertFalse(rustStorageManifest.exists())
        assertFalse(rustStorageSource.exists())
        assertTrue(cBootSource.exists())
        assertTrue(cBootChainSource.exists())
        assertTrue(cBootChainHeader.exists())
        assertTrue(kernelManifest.exists())
        assertTrue(kernelSource.exists())
        assertTrue(kernelBootChainSource.exists())
        assertTrue(kernelImageSource.exists())
        assertTrue(kernelKfsDeviceSource.exists())
        assertTrue(kernelKfsBlockIoSource.exists())
        assertFalse(kernelManifest.readText().contains("k16-boot-chain"))
        assertFalse(kernelManifest.readText().contains("k16-image"))
        assertFalse(kernelManifest.readText().contains("k16-storage"))

        val boot = cBootSource.readText()
        assertTrue(boot.contains("K16 BOOT"))
        assertTrue(boot.contains("load_k16e_from_storage0"))
        assertTrue(boot.contains("K16E_ABI_KIND_KERNEL"))

        val kernel = kernelSource.readText()
        assertTrue(kernel.contains("#![no_std]"))
        assertTrue(kernel.contains("#![no_main]"))
        assertTrue(kernel.contains("extern \"C\" fn _start() -> !"))
        assertTrue(kernel.contains("debug::print_kernel_ok()"))
        assertTrue(kernel.contains("init::launch()"))
    }

    @Test
    fun neoforgeFirmwareBuildDoesNotUseRuxCompilerOrDeletedGuestExamples() {
        val buildScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val neoforgeBuildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()

        assertTrue(buildScript.contains("guest/firmware/bios/bios.c"))
        assertTrue(neoforgeBuildScript.contains("alias(libs.plugins.k16FirmwareConvention)"))
        assertFalse(buildScript.contains("guest/kraftos/k16-bios"))
        assertTrue(buildScript.contains("guest/firmware/boot/boot.c"))
        assertTrue(buildScript.contains("guest/firmware/boot-chain/boot_chain.c"))
        assertFalse(buildScript.contains("guest/kraftos/k16-boot-chain"))
        assertFalse(buildScript.contains("guest/kraftos/k16-image"))
        assertFalse(buildScript.contains("guest/kraftos/k16-storage"))
        assertFalse(buildScript.contains("guest/kraftos/k16-boot/Cargo.toml"))
        assertFalse(buildScript.contains("guest/kraftos/k16-boot/src"))
        assertTrue(buildScript.contains("guest/kraftos/kernel"))
        assertFalse(buildScript.contains("tracked in #141"))
        assertFalse(buildScript.contains("--bin\",\\n            \"rux\""))
        assertFalse(buildScript.contains("ruxCompilerManifest"))
        assertFalse(buildScript.contains("k16_bios.rx"))
        assertFalse(buildScript.contains("kernel_loader.rx"))
        assertFalse(buildScript.contains("display_ok.rx"))
        assertFalse(buildScript.contains("host/k16-tools/examples"))
    }

    @Test
    fun activeBootChainTestsUseK16ArtifactsWithoutRuxSourceFixtures() {
        val testPaths =
            listOf(
                "host/k16-tools/tests/k16_volume_cli.rs",
                "host/k16-tools/tests/k16_storage_workflow_cli.rs",
            )

        for (path in testPaths) {
            val source = root.resolve(path).readText()

            assertTrue(source.contains("K16eAbiKind"))
            assertFalse(source.contains(".rx"), "$path should not use Rux source fixtures")
            assertFalse(source.contains("rux compile"), "$path should not invoke the Rux compiler")
            assertFalse(source.contains("--bin rux"), "$path should not invoke the Rux CLI")
            assertFalse(source.contains("examples/boot"), "$path should not read retired boot examples")
            assertFalse(source.contains("examples/kernel"), "$path should not read retired kernel examples")
            assertFalse(source.contains("examples/init"), "$path should not read retired init examples")
        }
    }

    @Test
    fun rustFirmwareGradleBuildsConfiguredCoreOrAllocArtifacts() {
        val buildScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()

        assertTrue(buildScript.contains("buildStd: String = \"core\""))
        assertTrue(buildScript.contains("-Zbuild-std=\$buildStd"))
        assertTrue(buildScript.contains("-Zjson-target-spec"))
        assertTrue(buildScript.contains("\"RUSTFLAGS\""))
        assertTrue(buildScript.contains("-C linker=\${toolchain.linker.absolutePath}"))
        assertTrue(buildScript.contains("-Cjump-tables=no"))
        assertFalse(buildScript.contains("build-std=std"))
        assertTrue(buildScript.contains("fun Project.compileK16GuestCFirmware("))
        assertTrue(buildScript.contains("compileK16GuestCFirmware("))
    }

    @Test
    fun activeAbiDocsUseK16FormatNames() {
        val abiDir = root.resolve("docs/abi")

        for (expected in
            listOf(
                "k16-cpu-v1.md",
                "k16-object-v1.md",
                "k16e-v1.md",
                "kfs-v1.md",
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
