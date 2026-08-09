package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayApplyResult
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayReplica
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedMask1Bpp
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedMaskInstanceBuffer
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16StaticStorageAttachment
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.KraftOsArtifactManifest
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16_VOLUME_MAGIC_BYTES
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val K16_KERNEL_LOAD_ADDR = 0x0000_4000
private const val K16_COMPUTER_MEMORY_BYTES = 1024 * 1024
private const val K16_TERMINAL_CELLS_ADDR = 0x0000_3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
private const val BIOS_FONT_MASK_ID = 0xffff_ff01u
private const val BIOS_INSTANCES_ID = 0xffff_ff02u
private const val KRAFTOS_FONT_MASK_ID = 1u
private const val KRAFTOS_INSTANCES_ID = 2u

private fun optionalSource(path: Path): String =
    path.takeIf { it.toFile().isFile }
        ?.readText()
        .orEmpty()

class K16FirmwareResourceTest {
    @Test
    fun removedRustSharedRuntimeProofIsNotBundled() {
        val source = Path.of("build.gradle.kts").readText()
        val guestWorkspace = Path.of("../../../guest/kraftos/Cargo.toml").readText()
        val guestLockfile = Path.of("../../../guest/kraftos/Cargo.lock").readText()

        listOf(
            "guest/kraftos/k16-shared-runtime",
            "guest/kraftos/k16-runtime-import-test",
            "k16SharedRuntime",
            "k16RuntimeImportTest",
            "compileK16SharedRuntime",
            "compileK16RuntimeImportTest",
            "libk16rt.kso",
            "runtime-import-test.kx",
            "k16rt_memcpy",
            "k16rt_memset",
            "k16rt_memmove",
            "k16rt_memcmp",
        ).forEach { removed ->
            assertFalse(source.contains(removed), "K16 firmware build should not reference removed $removed")
        }

        assertFalse(guestWorkspace.contains("k16-shared-runtime"))
        assertFalse(guestWorkspace.contains("k16-runtime-import-test"))
        assertFalse(guestLockfile.contains("name = \"k16-shared-runtime\""))
        assertFalse(guestLockfile.contains("name = \"k16-runtime-import-test\""))
    }

    @Test
    fun removedRustUserlandProofCratesAreNotBundled() {
        val source = Path.of("build.gradle.kts").readText()
        val guestWorkspace = Path.of("../../../guest/kraftos/Cargo.toml").readText()
        val guestLockfile = Path.of("../../../guest/kraftos/Cargo.lock").readText()
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        listOf(
            "guest/kraftos/k16-alloc-test",
            "guest/kraftos/k16-proc-test",
            "guest/kraftos/k16-syscall-fault-test",
            "guest/kraftos/k16-user-fault-test",
            "guest/kraftos/kraft-std",
            "k16AllocTest",
            "k16ProcTest",
            "k16SyscallFaultTest",
            "k16UserFaultTest",
            "kraftStd",
            "compileK16SystemAllocTest",
            "compileK16SystemProcTest",
            "compileK16SyscallFaultTest",
            "compileK16UserFaultTest",
            "alloc-test.kx",
            "proc-test.kx",
            "syscall-fault-test.kx",
            "user-fault-test.kx",
        ).forEach { removed ->
            assertFalse(source.contains(removed), "K16 firmware build should not reference removed $removed")
        }

        listOf(
            "k16-alloc-test",
            "k16-proc-test",
            "k16-syscall-fault-test",
            "k16-user-fault-test",
            "kraft-std",
        ).forEach { removed ->
            assertFalse(guestWorkspace.contains(removed), "guest workspace should not include removed $removed")
            assertFalse(guestLockfile.contains("name = \"$removed\""), "guest lockfile should not include removed $removed")
        }

        assertFalse(shellSource.contains("ALLOC_PROGRAM"), "C shell should not expose a removed Rust alloc-test launcher")
        assertFalse(shellSource.contains("alloc-test"), "C shell should not dispatch to a removed Rust alloc-test launcher")
    }

    @Test
    fun bundledK16FirmwareBuildUsesK16GradleSurface() {
        val source =
            Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val consumerSource = Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()
        val neoforgeBuildScript = Path.of("build.gradle.kts").readText()
        val rootBuildScript = Path.of("../../../build.gradle.kts").readText()
        val k16ToolchainSupport =
            Path.of("../../../build-scripts/src/main/kotlin/K16ToolchainSupport.kt").readText()

        assertTrue(neoforgeBuildScript.contains("alias(libs.plugins.k16FirmwareConvention)"))
        assertFalse(neoforgeBuildScript.contains("tasks.register(\"linkK16BiosFlash\")"))
        assertTrue(consumerSource.contains("id(\"k16-firmware-producer-convention\")"))
        assertTrue(source.contains("generated/k16-firmware-resources"))
        assertTrue(source.contains("generated/k16-firmware-artifacts"))
        assertTrue(source.contains("tasks.register(\"linkK16BiosFlash\")"))
        assertTrue(source.contains("fun Project.compileK16GuestRustBin("))
        assertTrue(source.contains("ProcessBuilder(command)"))
        assertTrue(source.contains("k16-cpu-helpers"))
        assertTrue(source.contains("host/k16-tools/Cargo.toml"))
        assertTrue(source.contains("rootProject.tasks.named(\"prepareK16Toolchain\")"))
        assertTrue(source.contains("resolveK16Toolchain()"))
        assertTrue(rootBuildScript.contains("prepareK16Toolchain"))
        assertTrue(rootBuildScript.contains("stageK16Toolchain"))
        assertTrue(rootBuildScript.contains("downloadK16ToolchainArchive"))
        assertTrue(k16ToolchainSupport.contains("config/k16-toolchain.json"))
        assertTrue(k16ToolchainSupport.contains(".toolchain/k16"))
        assertTrue(k16ToolchainSupport.contains("must not be a symlink"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_LD\")"))
        assertTrue(source.contains("-C linker="))
        assertTrue(source.contains("-C link-arg=--k16-target=\$k16Target"))
        assertTrue(source.contains("\"opt-level=z\""))
        assertTrue(source.contains("-Copt-level=z"))
        assertTrue(source.contains("guest/firmware/bios/bios.c"))
        assertTrue(source.contains("guest/firmware/boot/boot.c"))
        assertTrue(source.contains("guest/firmware/boot-chain/boot_chain.c"))
        assertTrue(source.contains("guest/firmware/boot-chain/boot_chain.h"))
        assertTrue(source.contains("fun Project.compileK16GuestCFirmware("))
        assertTrue(source.contains("fun Project.compileK16GuestCFixedImage("))
        assertTrue(source.contains("compileK16GuestCFirmware("))
        assertTrue(source.contains("compileK16GuestCFixedImage("))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 BIOS"))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 bootloader"))
        assertTrue(source.contains("k16Target = \"boot\""))
        assertTrue(source.contains("k16Target = \"kernel\""))
        assertFalse(source.contains("k16Target = \"program-dynamic\""))
        assertFalse(source.contains("k16Target = \"program\""))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16BiosObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemBootObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemKernelObject\")"))
        assertFalse(source.contains("--emit=obj"))
        assertTrue(source.contains("tasks.register<Exec>(\"compileK16SystemStorage0\")"))
        assertFalse(source.contains("guest/kraftos/k16-boot-chain"))
        assertFalse(source.contains("guest/kraftos/k16-image"))
        assertFalse(source.contains("guest/kraftos/k16-storage"))
        assertFalse(source.contains("guest/kraftos/k16-bios"))
        assertFalse(source.contains("guest/kraftos/k16-boot/Cargo.toml"))
        assertFalse(source.contains("guest/kraftos/k16-boot/src"))
        assertTrue(source.contains("guest/kraftos/kernel"))
        assertFalse(source.contains("guest/kraftos/k16-init"))
        assertFalse(source.contains("guest/kraftos/k16-shell"))
        assertFalse(source.contains("guest/kraftos/k16-ls"))
        assertFalse(source.contains("guest/kraftos/k16-cat"))
        assertFalse(source.contains("guest/kraftos/k16-uname"))
        assertFalse(source.contains("guest/kraftos/k16-cp"))
        assertFalse(source.contains("guest/kraftos/k16-mv"))
        assertFalse(source.contains("guest/kraftos/k16-stat"))
        assertFalse(source.contains("guest/kraftos/k16-write"))
        assertFalse(source.contains("guest/kraftos/k16-rm"))
        assertFalse(source.contains("guest/kraftos/k16-mkdir"))
        assertFalse(source.contains("guest/kraftos/k16-rmdir"))
        assertFalse(source.contains("guest/kraftos/k16-shared-runtime"))
        assertFalse(source.contains("guest/kraftos/k16-shared-smoke-runtime"))
        assertFalse(source.contains("guest/kraftos/k16-runtime-import-test"))
        assertFalse(source.contains("guest/kraftos/k16-shared-runtime-test"))
        assertFalse(source.contains("guest/kraftos/k16-alloc-test"))
        assertFalse(source.contains("guest/kraftos/k16-memory"))
        assertFalse(source.contains("guest/kraftos/runtime/src/no_core_helpers.rs"))
        assertTrue(source.contains("guest/platform/k16/memory-helpers.rs"))
        assertTrue(source.contains("k16MemoryHelpersRuntimeSource"))
        assertFalse(source.contains("guest/kraftos/k16-hosted-cat"))
        assertFalse(source.contains("guest/kraftos/k16-hosted-hello"))
        assertFalse(source.contains("guest/kraftos/k16-proc-test"))
        assertFalse(source.contains("k16InitManifest"))
        assertFalse(source.contains("k16InitSource"))
        assertTrue(source.contains("k16CSystemInitSource"))
        assertFalse(source.contains("k16ShellManifest"))
        assertFalse(source.contains("k16ShellSource"))
        assertTrue(source.contains("k16CSystemShellSource"))
        assertFalse(source.contains("k16LsManifest"))
        assertFalse(source.contains("k16LsSource"))
        assertTrue(source.contains("k16CSystemLsSource"))
        assertFalse(source.contains("k16UnameManifest"))
        assertFalse(source.contains("k16UnameSource"))
        assertTrue(source.contains("k16CSystemUnameSource"))
        assertFalse(source.contains("k16CatManifest"))
        assertFalse(source.contains("k16CatSource"))
        assertTrue(source.contains("k16CSystemCatSource"))
        assertFalse(source.contains("k16CpManifest"))
        assertFalse(source.contains("k16CpSource"))
        assertTrue(source.contains("k16CSystemCpSource"))
        assertFalse(source.contains("k16MvManifest"))
        assertFalse(source.contains("k16MvSource"))
        assertTrue(source.contains("k16CSystemMvSource"))
        assertFalse(source.contains("k16StatManifest"))
        assertFalse(source.contains("k16StatSource"))
        assertTrue(source.contains("k16CSystemStatSource"))
        assertFalse(source.contains("k16WriteManifest"))
        assertFalse(source.contains("k16WriteSource"))
        assertTrue(source.contains("k16CSystemWriteSource"))
        assertFalse(source.contains("k16RmManifest"))
        assertFalse(source.contains("k16RmSource"))
        assertTrue(source.contains("k16CSystemRmSource"))
        assertFalse(source.contains("k16MkdirManifest"))
        assertFalse(source.contains("k16MkdirSource"))
        assertTrue(source.contains("k16CSystemMkdirSource"))
        assertFalse(source.contains("k16MemoryManifest"))
        assertFalse(source.contains("k16MemorySource"))
        assertFalse(source.contains("k16RmdirManifest"))
        assertFalse(source.contains("k16RmdirSource"))
        assertTrue(source.contains("k16CSystemRmdirSource"))
        assertFalse(source.contains("k16SharedRuntimeManifest"))
        assertFalse(source.contains("k16RuntimeImportTestManifest"))
        assertFalse(source.contains("k16AllocTestManifest"))
        assertFalse(source.contains("k16AllocTestSource"))
        assertFalse(source.contains("k16HostedCatManifest"))
        assertFalse(source.contains("k16HostedCatSource"))
        assertFalse(source.contains("k16HostedHelloManifest"))
        assertFalse(source.contains("k16HostedHelloSource"))
        assertFalse(source.contains("k16ProcTestManifest"))
        assertFalse(source.contains("k16ProcTestSource"))
        assertTrue(source.contains("generatedK16CSystemInitTarget"))
        assertTrue(source.contains("generatedK16CSystemShellTarget"))
        assertFalse(source.contains("generatedK16LsTarget"))
        assertTrue(source.contains("generatedK16CSystemLsTarget"))
        assertFalse(source.contains("generatedK16UnameTarget"))
        assertTrue(source.contains("generatedK16CSystemUnameTarget"))
        assertFalse(source.contains("generatedK16CatTarget"))
        assertTrue(source.contains("generatedK16CSystemCatTarget"))
        assertFalse(source.contains("generatedK16CpTarget"))
        assertTrue(source.contains("generatedK16CSystemCpTarget"))
        assertFalse(source.contains("generatedK16MvTarget"))
        assertTrue(source.contains("generatedK16CSystemMvTarget"))
        assertFalse(source.contains("generatedK16StatTarget"))
        assertTrue(source.contains("generatedK16CSystemStatTarget"))
        assertFalse(source.contains("generatedK16WriteTarget"))
        assertTrue(source.contains("generatedK16CSystemWriteTarget"))
        assertFalse(source.contains("generatedK16RmTarget"))
        assertTrue(source.contains("generatedK16CSystemRmTarget"))
        assertFalse(source.contains("generatedK16MkdirTarget"))
        assertTrue(source.contains("generatedK16CSystemMkdirTarget"))
        assertFalse(source.contains("generatedK16RmdirTarget"))
        assertTrue(source.contains("generatedK16CSystemRmdirTarget"))
        assertFalse(source.contains("generatedK16SharedRuntimeTarget"))
        assertFalse(source.contains("generatedK16RuntimeImportTestTarget"))
        assertFalse(source.contains("generatedK16AllocTestTarget"))
        assertFalse(source.contains("generatedK16HostedCatTarget"))
        assertFalse(source.contains("generatedK16HostedHelloTarget"))
        assertFalse(source.contains("generatedK16ProcTestTarget"))
        assertTrue(source.contains("k16InitArtifact"))
        assertTrue(source.contains("k16ShellArtifact"))
        assertTrue(source.contains("k16LsArtifact"))
        assertTrue(source.contains("k16CatArtifact"))
        assertTrue(source.contains("k16MvArtifact"))
        assertTrue(source.contains("k16StatArtifact"))
        assertTrue(source.contains("k16WriteArtifact"))
        assertTrue(source.contains("k16RmArtifact"))
        assertTrue(source.contains("k16MkdirArtifact"))
        assertTrue(source.contains("k16RmdirArtifact"))
        assertFalse(source.contains("k16SharedRuntimeArtifact"))
        assertTrue(source.contains("k16SharedKraftArtifact"))
        assertFalse(source.contains("k16RuntimeImportTestArtifact"))
        assertFalse(source.contains("k16AllocTestArtifact"))
        assertFalse(source.contains("k16HostedCatArtifact"))
        assertFalse(source.contains("k16HostedHelloArtifact"))
        assertFalse(source.contains("k16ProcTestArtifact"))
        assertFalse(source.contains("k16CHostedCatArtifact"))
        assertTrue(source.contains("k16CLibcIncludeSource"))
        assertTrue(source.contains("k16CLibcStartupSource"))
        assertTrue(source.contains("k16CLibcSyscallSource"))
        assertTrue(source.contains("guest/kraftos/libc/crt0.c"))
        assertTrue(source.contains("guest/kraftos/libc/syscalls.c"))
        assertTrue(source.contains("guest/kraftos/libc/include"))
        assertTrue(source.contains("guest/kraftos/userland/init/init.c"))
        assertTrue(source.contains("guest/kraftos/userland/shell/shell.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/uname.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/ls.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/cat.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/cp.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/mv.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/stat.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/write.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/rm.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/mkdir.c"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/rmdir.c"))
        assertTrue(source.contains("guest/kraftos/data/etc/motd"))
        assertFalse(source.contains("guest/kraftos/k16-cat/motd.txt"))
        assertTrue(source.contains("fun Project.compileK16GuestCProgram("))
        assertTrue(source.contains("--target=k16"))
        assertFalse(source.contains("\"compileK16CHostedCat\""))
        assertTrue(source.contains("val k16ProductionStorageEntries ="))
        assertTrue(source.contains("val k16DevelopmentOnlyStorageEntries ="))
        assertTrue(source.contains("val k16SharedLibraryStorageEntries ="))
        assertTrue(source.contains("compileK16SystemInit"))
        assertTrue(source.contains("compileK16SystemShell"))
        assertTrue(source.contains("compileK16SystemLs"))
        assertTrue(source.contains("compileK16SystemCat"))
        assertTrue(source.contains("compileK16SystemMv"))
        assertTrue(source.contains("compileK16SystemStat"))
        assertTrue(source.contains("compileK16SystemWrite"))
        assertTrue(source.contains("compileK16SystemRm"))
        assertTrue(source.contains("compileK16SystemMkdir"))
        assertTrue(source.contains("compileK16SystemRmdir"))
        assertFalse(source.contains("compileK16SharedRuntime"))
        assertTrue(source.contains("compileK16SharedKraft"))
        assertFalse(source.contains("compileK16RuntimeImportTest"))
        assertFalse(source.contains("compileK16SystemAllocTest"))
        assertFalse(source.contains("compileK16HostedCat"))
        assertFalse(source.contains("compileK16HostedHello"))
        assertFalse(source.contains("compileK16SystemProcTest"))
        assertTrue(source.contains("putK16SystemStorage0Init"))
        assertFalse(source.contains("binName = \"k16-init\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 init launcher"))
        assertTrue(
            source.contains("targetDir = generatedK16CSystemInitTarget.get().asFile"),
            "production init should build from the C init source",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemInitSource.asFile)"),
            "production init should build from the C init source and import libc/syscall wrappers from libkraft",
        )
        assertTrue(
            source.contains("dylibs = listOf(k16SharedKraftArtifact.get().asFile)"),
            "production init should import process calls from libkraft",
        )
        assertFalse(source.contains("binName = \"k16-shell\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 shell"))
        assertTrue(
            source.contains("targetDir = generatedK16CSystemShellTarget.get().asFile"),
            "production shell should build from the C shell source",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemShellSource.asFile)"),
            "production shell should build from the C shell source and import libc/syscall wrappers from libkraft",
        )
        assertFalse(source.contains("binName = \"k16-ls\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 ls utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemLsSource.asFile)"),
            "production ls should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-uname\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 uname utility"))
        assertTrue(
            source.contains(
                "output = k16UnameArtifact.get().asFile,\n                mapOutput = k16UnameMapArtifact.get(),",
            ),
            "production uname should write the production /bin/uname.kx artifact",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemUnameSource.asFile)"),
            "production uname should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-cat\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 cat utility"))
        assertTrue(
            source.contains(
                "output = k16CatArtifact.get().asFile,\n                mapOutput = k16CatMapArtifact.get(),",
            ),
            "production cat should write the production /bin/cat.kx artifact",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemCatSource.asFile)"),
            "production cat should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-cp\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 cp utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemCpSource.asFile)"),
            "production cp should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-mv\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 mv utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemMvSource.asFile)"),
            "production mv should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-stat\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 stat utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemStatSource.asFile)"),
            "production stat should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-write\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 write utility"))
        assertTrue(
            source.contains(
                "output = k16WriteArtifact.get().asFile,\n                mapOutput = k16WriteMapArtifact.get(),",
            ),
            "production write should write the production /bin/write.kx artifact",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemWriteSource.asFile)"),
            "production write should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-rm\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 rm utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemRmSource.asFile)"),
            "production rm should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-mkdir\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 mkdir utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemMkdirSource.asFile)"),
            "production mkdir should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-rmdir\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 rmdir utility"))
        assertTrue(
            source.contains("sources = listOf(k16CSystemRmdirSource.asFile)"),
            "production rmdir should build from the C coreutils source",
        )
        assertFalse(source.contains("binName = \"k16-shared-runtime\""))
        assertTrue(source.contains("val k16CArchRuntimeSource = rootProject.layout.projectDirectory.file(\"guest/platform/k16/cpu-helpers.kasm\")"))
        assertTrue(source.contains("compileK16ArchRuntimeObject("))
        assertTrue(source.contains("val k16CLibkraftSource = rootProject.layout.projectDirectory.file(\"guest/kraftos/lib/libkraft/libkraft.c\")"))
        assertTrue(source.contains("compileK16GuestCSharedObject("))
        assertFalse(source.contains("includeCpuHelpers = true"))
        assertFalse(source.contains("binName = \"k16-shared-kraft\""))
        assertTrue(source.contains("add(\"--dylib\")"))
        assertFalse(source.contains("libkraft.kso:kraft_write_all"))
        assertFalse(source.contains("libkraft.kso:kraft_exit"))
        assertFalse(source.contains("libk16rt.kso:k16rt_memcpy"))
        assertFalse(source.contains("libk16rt.kso:k16rt_memset"))
        assertFalse(source.contains("libk16rt.kso:k16rt_memmove"))
        assertFalse(source.contains("libk16rt.kso:k16rt_memcmp"))
        assertFalse(source.contains("binName = \"k16-runtime-import-test\""))
        assertFalse(source.contains("binName = \"k16-alloc-test\""))
        assertFalse(source.contains("binName = \"k16-hosted-cat\""))
        assertFalse(source.contains("binName = \"k16-hosted-hello\""))
        assertFalse(source.contains("binName = \"k16-proc-test\""))
        assertFalse(
            source.contains(
                "output = k16ShellArtifact.get().asFile,\n                mapOutput = k16ShellMapArtifact.get(),\n                buildStd = \"core,alloc\"",
            ),
            "production shell should not require Rust alloc once C-built",
        )
        assertTrue(source.contains("\"/bin\""))
        assertTrue(source.contains("\"/lib\""))
        assertTrue(source.contains("\"/etc\""))
        assertTrue(source.contains("\"/bin/init.kx\""))
        assertTrue(source.contains("\"/bin/shell.kx\""))
        assertTrue(source.contains("\"/bin/ls.kx\""))
        assertTrue(source.contains("\"/bin/cat.kx\""))
        assertTrue(source.contains("\"/bin/cp.kx\""))
        assertTrue(source.contains("\"/bin/mv.kx\""))
        assertTrue(source.contains("\"/bin/stat.kx\""))
        assertTrue(source.contains("\"/bin/write.kx\""))
        assertTrue(source.contains("\"/bin/rm.kx\""))
        assertTrue(source.contains("\"/bin/mkdir.kx\""))
        assertTrue(source.contains("\"/bin/rmdir.kx\""))
        assertFalse(source.contains("\"/lib/libk16rt.kso\""))
        assertTrue(source.contains("\"/lib/libkraft.kso\""))
        assertFalse(source.contains("\"/bin/runtime-import-test.kx\""))
        assertFalse(source.contains("\"/bin/alloc-test.kx\""))
        assertFalse(source.contains("\"/bin/hosted-cat.kx\""))
        assertFalse(source.contains("\"/bin/hosted-hello.kx\""))
        assertFalse(source.contains("\"/bin/proc-test.kx\""))
        val productionEntriesIndex = source.indexOf("val k16ProductionStorageEntries =")
        val developmentOnlyEntriesIndex = source.indexOf("val k16DevelopmentOnlyStorageEntries =")
        val sharedLibraryEntriesIndex = source.indexOf("val k16SharedLibraryStorageEntries =")
        val storageTaskIndex = source.indexOf("val putK16SystemStorage0Init =")
        assertTrue(productionEntriesIndex >= 0, "production storage entries should be declared explicitly")
        assertTrue(developmentOnlyEntriesIndex > productionEntriesIndex, "dev-only entries should follow production entries")
        assertTrue(sharedLibraryEntriesIndex > productionEntriesIndex, "shared library entries should be declared separately")
        assertTrue(storageTaskIndex > developmentOnlyEntriesIndex, "storage tasks should consume declared entry groups")
        assertTrue(
            source.substring(productionEntriesIndex, developmentOnlyEntriesIndex).contains("\"/bin/cat.kx\""),
            "normal cat should be a production utility",
        )
        assertTrue(
            source.substring(sharedLibraryEntriesIndex, storageTaskIndex).contains("\"/lib/libkraft.kso\""),
            "shared library entries should include the kraft shared userland library",
        )
        assertFalse(source.contains("\"/bin/hosted-cat.kx\""), "hosted-cat should not be bundled")
        assertFalse(source.contains("\"/bin/hosted-hello.kx\""), "hosted-hello should not be bundled")
        assertFalse(
            source.contains("\"/bin/c-cat.kx\""),
            "C cat proof should be removed once production /bin/cat.kx is C-built",
        )
        assertTrue(source.contains("\"/etc/motd\""))
        assertTrue(source.contains("\"extract-partition\""))
        assertTrue(source.contains("\"replace-partition\""))
        assertTrue(source.contains("dir(\"guest/kraftos/kernel/src\")"))
        assertFalse(source.contains("dir(\"guest/kraftos/k16-shell/src\")"))
        assertTrue(source.contains("inputs.dir(k16KernelSource)"))
        assertFalse(source.contains("inputs.dir(k16ShellSource)"))
        assertTrue(source.contains("toolchain.cli.absolutePath"))
        assertFalse(source.contains(".toolchain/build/cargo/k16-tools"))
        assertFalse(source.contains("environment(\"CARGO_TARGET_DIR\""))
        assertFalse(source.contains("generated/rux-firmware-"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileRux"))
        assertFalse(source.contains("--bin\", \"rux\""))

        val toolchainConfig = Path.of("../../../config/k16-toolchain.json").readText()
        assertTrue(toolchainConfig.contains("\"pin\""))
        assertTrue(toolchainConfig.contains("\"linux-x86_64\""))
        assertTrue(toolchainConfig.contains("\"bin/k16-ld\""))
        assertTrue(toolchainConfig.contains("\"bin/k16\""))
    }

    @Test
    fun kraftOsProductionBundleIsResourcePackagingBoundary() {
        val producerSource =
            Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val consumerSource = Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()

        assertTrue(producerSource.contains("val generatedKraftOsBundles ="))
        assertTrue(producerSource.contains("val generatedKraftOsProductionBundle ="))
        assertTrue(producerSource.contains("generated/kraftos-bundles"))
        assertTrue(producerSource.contains("production"))
        assertTrue(producerSource.contains("tasks.register<org.gradle.api.tasks.Sync>(\"assembleKraftOsProductionBundle\")"))
        assertTrue(producerSource.contains("from(generatedK16FirmwareResources)"))
        assertTrue(producerSource.contains("into(generatedKraftOsProductionBundle)"))
        assertTrue(consumerSource.contains("resources.srcDir(generatedKraftOsProductionBundle)"))
        assertFalse(consumerSource.contains("resources.srcDir(generatedK16FirmwareResources)"))
        assertTrue(consumerSource.contains("dependsOn(\"assembleKraftOsProductionBundle\")"))
    }

    @Test
    fun bundledK16FirmwareBuildIncludesYesCoreutil() {
        val source =
            Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()

        assertTrue(source.contains("generatedK16CSystemYesTarget"))
        assertTrue(source.contains("k16CSystemYesSource"))
        assertTrue(source.contains("guest/kraftos/userland/coreutils/yes.c"))
        assertTrue(source.contains("k16YesArtifact"))
        assertTrue(source.contains("k16YesMapArtifact"))
        assertTrue(source.contains("compileK16SystemYes"))
        assertFalse(source.contains("binName = \"k16-yes\""))
        assertTrue(source.contains("description = \"Compiles and links the bundled C K16 yes utility"))
        assertTrue(
            source.contains(
                "output = k16YesArtifact.get().asFile,\n                mapOutput = k16YesMapArtifact.get(),",
            ),
            "production yes should write the production /bin/yes.kx artifact",
        )
        assertTrue(
            source.contains("sources = listOf(k16CSystemYesSource.asFile)"),
            "production yes should build from the C coreutils source",
        )
        assertTrue(source.contains("\"/bin/yes.kx\" to k16YesArtifact"))
        assertTrue(
            source.contains(
                "compileK16SystemWrite, compileK16SystemYes, compileK16SystemRm",
            ),
            "production storage image should depend on the yes utility artifact",
        )
    }

    @Test
    fun k16SourceBuiltDevToolchainSmokeTaskUsesStagedToolchain() {
        val rootBuildScript = Path.of("../../../build.gradle.kts").readText()

        assertTrue(rootBuildScript.contains("tasks.register<Exec>(\"testK16SourceBuiltDevToolchain\")"))
        assertTrue(rootBuildScript.contains("dependsOn(stageK16SourceBuiltDevToolchain)"))
        assertTrue(rootBuildScript.contains("workingDir(rootProject.file(\"host/k16-tools\"))"))
        assertTrue(rootBuildScript.contains("k16SourceBuiltDevToolchainInstallRoot.resolve(\"bin/cargo\").absolutePath"))
        assertTrue(rootBuildScript.contains("\"test\",\n            \"-j\",\n            k16HostToolsBuildJobs"))
        assertTrue(rootBuildScript.contains("environment(\"K16_CARGO\", k16SourceBuiltDevToolchainInstallRoot.resolve(\"bin/cargo\").absolutePath)"))
        assertTrue(rootBuildScript.contains("environment(\"K16_RUSTC\", k16SourceBuiltDevToolchainInstallRoot.resolve(\"bin/rustc\").absolutePath)"))
        assertTrue(rootBuildScript.contains("environment(\n            \"CARGO_TARGET_DIR\""))
        assertTrue(rootBuildScript.contains("k16HostToolsTargetRoot.resolve(\"source-built-dev-tests\").absolutePath"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(rootBuildScript.contains("providers.environmentVariable(\"K16_RUSTC\")"))
    }

    @Test
    fun replacedRustUserlandCratesAreRemovedFromGuestWorkspace() {
        val guestRoot = Path.of("../../../guest/kraftos")
        val workspace = guestRoot.resolve("Cargo.toml").readText()

        assertFalse(workspace.contains("\"k16-init\""), "C init should not leave a Rust init workspace member")
        assertFalse(workspace.contains("\"k16-shell\""), "C shell should not leave a Rust shell workspace member")
        assertFalse(Files.exists(guestRoot.resolve("k16-init")), "C init should replace the legacy Rust init crate")
        assertFalse(Files.exists(guestRoot.resolve("k16-shell")), "C shell should replace the legacy Rust shell crate")
    }

    @Test
    fun bundledK16UserlandMapArtifactsAreGeneratedWithoutHostedRustProofs() {
        val source =
            Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()

        assertFalse(source.contains("val k16HostedCatMapArtifact ="))
        assertFalse(source.contains("val k16HostedHelloMapArtifact ="))
        assertFalse(source.contains("outputs.file(k16HostedCatMapArtifact)"))
        assertFalse(source.contains("outputs.file(k16HostedHelloMapArtifact)"))
        assertFalse(source.contains("k16HostedCatArtifact"))
        assertFalse(source.contains("k16HostedHelloArtifact"))
        assertTrue(source.contains("mapOutput: File"))
        assertTrue(source.contains("-C link-arg=--map"))
        assertTrue(source.contains("-C link-arg=\${mapOutput.absolutePath}"))
    }

    @Test
    fun bundledK16UserlandSizeReportTaskUsesProductionMaps() {
        val source =
            Path.of("../../../build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val docs = Path.of("../../../docs/toolchains/k16-userland-size-report.md").readText()

        assertTrue(source.contains("tasks.register(\"reportK16UserlandSize\")"))
        assertTrue(source.contains("description = \"Reports K16 production and development storage and userland sizes.\""))
        assertTrue(source.contains("val k16ProductionUserlandMapArtifacts ="))
        assertTrue(source.contains("val k16DevelopmentOnlyMapArtifacts ="))
        assertTrue(source.contains("val k16SharedLibraryMapArtifacts ="))
        assertTrue(source.contains("dependsOn(putK16SystemStorage0Init, putK16DevelopmentStorage0TestPrograms)"))
        assertTrue(source.contains("println(\"storage_image name=production"))
        assertTrue(source.contains("println(\"storage_image name=development"))
        assertTrue(source.contains("println(\"storage_image_delta name=development_minus_production"))
        assertTrue(source.contains("storage_entries group=production"))
        assertTrue(source.contains("storage_entries group=shared_libraries"))
        assertTrue(source.contains("storage_entries group=development_only"))
        assertTrue(source.contains("storage_entries group=development_total"))
        assertTrue(source.contains("println(\"map_section name=production_userland\")"))
        assertTrue(source.contains("println(\"map_section name=shared_libraries\")"))
        assertTrue(source.contains("println(\"map_section name=development_only\")"))
        assertTrue(source.contains("args.add(\"size-report\")"))
        assertTrue(source.contains("runK16SizeReport(k16ProductionUserlandMapArtifacts)"))
        assertTrue(source.contains("runK16SizeReport(k16SharedLibraryMapArtifacts)"))
        assertTrue(source.contains("runK16SizeReport(k16DevelopmentOnlyMapArtifacts)"))
        assertTrue(source.contains("val stdout = process.inputStream.bufferedReader().readText()"))
        assertTrue(source.contains("print(stdout)"))
        assertTrue(
            docs.contains("storage_image name=production") &&
                docs.contains("storage_image_delta name=development_minus_production") &&
                docs.contains("storage_entries group=development_only") &&
                docs.contains("map_section name=development_only"),
            "size-report docs should describe storage and dev-only sections",
        )
    }

    @Test
    fun bundledK16BiosFlashResourceExists() {
        val bytes =
            K16BiosFlashWorkspace.loadBiosFlashResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 8, "K16 BIOS flash resource should not be empty")
        assertEquals(0, bytes.size % 2, "K16 BIOS flash resource should contain whole words")
    }

    @Test
    fun bundledK16BiosFlashResourceIsRawFlash() {
        val resource =
            javaClass.classLoader.getResourceAsStream("firmware/k16-bios.kflash")
                ?: error("raw K16 BIOS flash resource should exist")

        val bytes = resource.use { it.readBytes() }
        assertContentEquals(
            bytes,
            K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader),
        )
    }

    @Test
    fun bundledKraftOsArtifactManifestResourceExists() {
        val manifest = KraftOsArtifactManifest.load(classLoader = javaClass.classLoader)

        assertEquals(1, manifest.schema)
        assertEquals("k16", manifest.target)
        assertEquals("development", manifest.profile)
        assertEquals("firmware/k16-bios.kflash", manifest.biosFlash.resource)
        assertEquals("kflash", manifest.biosFlash.format)
        assertEquals("firmware/k16-system-storage0-dev.kv", manifest.systemStorage0.resource)
        assertEquals("kfs-kv", manifest.systemStorage0.format)
        assertEquals("firmware/c-sdk-v1.kv", manifest.sdkArtifact("c_sdk_v1").resource)
        assertEquals("kfs-kv", manifest.sdkArtifact("c_sdk_v1").format)
        assertEquals("firmware/sdk-fixture-v1.kv", manifest.sdkArtifact("sdk_fixture_v1").resource)
        assertEquals(setOf("c_sdk_v1", "sdk_fixture_v1"), manifest.sdkArtifacts.keys)
        assertTrue(
            javaClass.classLoader.getResource(manifest.biosFlash.resource) != null,
            "manifest BIOS flash resource should exist",
        )
        assertTrue(
            javaClass.classLoader.getResource(manifest.systemStorage0.resource) != null,
            "manifest storage0 resource should exist",
        )
        assertTrue(
            javaClass.classLoader.getResource(manifest.sdkArtifact("c_sdk_v1").resource) != null,
            "manifest C SDK resource should exist",
        )
        assertTrue(
            javaClass.classLoader.getResource(manifest.sdkArtifact("sdk_fixture_v1").resource) != null,
            "manifest SDK fixture resource should exist",
        )

        val firstCSdk =
            javaClass.classLoader.getResourceAsStream(manifest.sdkArtifact("c_sdk_v1").resource)
                ?.use { it.readBytes() }
                ?: error("manifest C SDK resource should resolve")
        val secondCSdk =
            javaClass.classLoader.getResourceAsStream(manifest.sdkArtifact("c_sdk_v1").resource)
                ?.use { it.readBytes() }
                ?: error("manifest C SDK resource should resolve repeatedly")
        assertContentEquals(firstCSdk, secondCSdk)
        assertEquals(4 * 1024 * 1024, firstCSdk.size - 16, "C SDK payload should be exactly 4 MiB")
    }

    @Test
    fun generatedKraftOsProductionBundleContainsRuntimeArtifacts() {
        val bundle = Path.of("build/generated/kraftos-bundles/production")
        val manifest = bundle.resolve("firmware/kraftos-artifacts.properties")
        val biosFlash = bundle.resolve("firmware/k16-bios.kflash")
        val systemStorage0 = bundle.resolve("firmware/k16-system-storage0.kv")
        val cSdk = bundle.resolve("firmware/c-sdk-v1.kv")
        val candidate = Path.of("build/generated/k16-native-tinycc/c-sdk-candidate.kv")

        assertTrue(Files.exists(manifest), "production bundle should contain the artifact manifest")
        assertTrue(Files.exists(biosFlash), "production bundle should contain the BIOS flash image")
        assertTrue(Files.exists(systemStorage0), "production bundle should contain the initial system storage volume")
        assertTrue(Files.exists(cSdk), "production bundle should contain the immutable C SDK volume")
        assertTrue(Files.exists(candidate), "production C SDK must retain its proven candidate source")
        assertTrue(manifest.readText().contains("profile=production"))
        assertTrue(manifest.readText().contains("artifact.sdk.c_sdk_v1.resource=firmware/c-sdk-v1.kv"))
        assertTrue(manifest.readText().contains("artifact.sdk.c_sdk_v1.format=kfs-kv"))
        assertFalse(manifest.readText().contains("artifact.sdk.c_sdk_candidate"))
        assertFalse(Files.exists(bundle.resolve("firmware/sdk-fixture-v1.kv")))
        assertTrue(biosFlash.readBytes().size > 8, "bundled BIOS flash should not be empty")
        assertTrue(systemStorage0.readBytes().size > 512, "bundled storage0 volume should not be empty")
        assertContentEquals(candidate.readBytes(), cSdk.readBytes(), "production C SDK must preserve candidate bytes")
    }

    @Test
    fun bundledK16SystemStorage0VolumeResourceExists() {
        val bytes =
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 512, "K16 system storage0 volume resource should not be empty")
        assertContentEquals(K16_VOLUME_MAGIC_BYTES, bytes.copyOfRange(0, K16_VOLUME_MAGIC_BYTES.size))
    }

    @Test
    fun bundledK16SystemStorage0ContainsInitProgram() {
        val workspace = createTempDirectory("k16-init-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val init = workspace.resolve("init.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/init.kx",
            init.toString(),
        )

        val bytes = init.readBytes()
        assertTrue(bytes.size > 52, "bundled /bin/init.kx should be a non-empty K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val metadata = bytes.decodeToString()
        assertEquals(5, version, "bundled /bin/init.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/init.kx must use K16E abi kind program")
        assertTrue(metadata.contains("libkraft.kso"), "bundled /bin/init.kx should declare libkraft.kso")
    }

    @Test
    fun bundledK16SystemStorage0ContainsUnameProgram() {
        val workspace = createTempDirectory("k16-uname-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val uname = workspace.resolve("uname.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/uname.kx",
            uname.toString(),
        )

        val bytes = uname.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/uname.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/uname.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/uname.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(
            metadata.contains("libkraft.kso"),
            "bundled /bin/uname.kx should declare libkraft.kso as a needed library",
        )
    }

    @Test
    fun bundledK16SystemStorage0ContainsCatProgramAndMotdFile() {
        val workspace = createTempDirectory("k16-cat-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val cat = workspace.resolve("cat.kx")
        val motd = workspace.resolve("motd.txt")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/cat.kx",
            cat.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/etc/motd",
            motd.toString(),
        )

        val bytes = cat.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/cat.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/cat.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/cat.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(
            metadata.contains("libkraft.kso"),
            "bundled /bin/cat.kx should declare libkraft.kso as a needed library",
        )
        listOf("kraft_open", "kraft_sys_read", "kraft_sys_write", "kraft_sys_close").forEach { symbol ->
            assertTrue(
                metadata.contains(symbol),
                "bundled /bin/cat.kx should import $symbol from libkraft",
            )
        }
        assertEquals("K16 FS OK\n", motd.readText())
    }

    @Test
    fun bundledK16DevelopmentStorage0ExcludesHostedCatProgram() {
        val workspace = createTempDirectory("k16-hosted-cat-storage-test-")
        val storage0 = workspace.resolve("storage0-dev.kv")
        val root = workspace.resolve("root.kfs")
        val cat = workspace.resolve("hosted-cat.kx")
        storage0.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/hosted-cat.kx",
            cat.toString(),
        )
    }

    @Test
    fun bundledK16SystemStorage0ContainsCpProgram() {
        val workspace = createTempDirectory("k16-cp-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val cp = workspace.resolve("cp.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/cp.kx",
            cp.toString(),
        )

        val bytes = cp.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/cp.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/cp.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/cp.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(metadata.contains("libkraft.kso"), "bundled /bin/cp.kx should declare libkraft.kso")
        listOf("kraft_open", "kraft_sys_read", "kraft_sys_write", "kraft_sys_close").forEach { symbol ->
            assertTrue(metadata.contains(symbol), "bundled /bin/cp.kx should import $symbol from libkraft")
        }
    }

    @Test
    fun bundledK16SystemStorage0ContainsMvProgram() {
        val workspace = createTempDirectory("k16-mv-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val mv = workspace.resolve("mv.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/mv.kx",
            mv.toString(),
        )

        val bytes = mv.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/mv.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/mv.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/mv.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(metadata.contains("libkraft.kso"), "bundled /bin/mv.kx should declare libkraft.kso")
        listOf("kraft_stat", "kraft_rename", "kraft_sys_write").forEach { symbol ->
            assertTrue(metadata.contains(symbol), "bundled /bin/mv.kx should import $symbol from libkraft")
        }
    }

    @Test
    fun bundledK16SystemStorage0ContainsLsProgram() {
        val workspace = createTempDirectory("k16-ls-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val ls = workspace.resolve("ls.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/ls.kx",
            ls.toString(),
        )

        val bytes = ls.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/ls.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/ls.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/ls.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(metadata.contains("libkraft.kso"), "bundled /bin/ls.kx should declare libkraft.kso")
        listOf("kraft_read_dir", "kraft_sys_write").forEach { symbol ->
            assertTrue(metadata.contains(symbol), "bundled /bin/ls.kx should import $symbol from libkraft")
        }
    }

    @Test
    fun bundledK16SystemStorage0ContainsStatProgram() {
        val workspace = createTempDirectory("k16-stat-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val stat = workspace.resolve("stat.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/stat.kx",
            stat.toString(),
        )

        val bytes = stat.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/stat.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/stat.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/stat.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(metadata.contains("libkraft.kso"), "bundled /bin/stat.kx should declare libkraft.kso")
        listOf("kraft_stat", "kraft_sys_write").forEach { symbol ->
            assertTrue(metadata.contains(symbol), "bundled /bin/stat.kx should import $symbol from libkraft")
        }
    }

    @Test
    fun bundledK16SystemStorage0ContainsWriteProgram() {
        val workspace = createTempDirectory("k16-write-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val write = workspace.resolve("write.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/write.kx",
            write.toString(),
        )

        val bytes = write.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/write.kx should be a non-empty imported dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, version, "bundled /bin/write.kx must use imported dynamic K16E v5")
        assertEquals(3, abiKind, "bundled /bin/write.kx must use K16E abi kind program")
        val metadata = bytes.decodeToString()
        assertTrue(
            metadata.contains("libkraft.kso"),
            "bundled /bin/write.kx should declare libkraft.kso as a needed library",
        )
        listOf("kraft_open", "kraft_sys_write", "kraft_sys_close").forEach { symbol ->
            assertTrue(
                metadata.contains(symbol),
                "bundled /bin/write.kx should import $symbol from libkraft",
            )
        }
    }

    @Test
    fun bundledK16SystemStorage0ExcludesDevelopmentTestPrograms() {
        val workspace = createTempDirectory("k16-prod-storage-layout-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val allocTest = workspace.resolve("alloc-test.kx")
        val procTest = workspace.resolve("proc-test.kx")
        val runtimeImportTest = workspace.resolve("runtime-import-test.kx")
        val hostedCat = workspace.resolve("hosted-cat.kx")
        val hostedHello = workspace.resolve("hosted-hello.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/alloc-test.kx",
            allocTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/proc-test.kx",
            procTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/runtime-import-test.kx",
            runtimeImportTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/hosted-cat.kx",
            hostedCat.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/hosted-hello.kx",
            hostedHello.toString(),
        )
    }

    @Test
    fun bundledK16SystemStorage0ContainsSharedUserlandLibrary() {
        val workspace = createTempDirectory("k16-shared-library-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val k16Runtime = workspace.resolve("libk16rt.kso")
        val kraftRuntime = workspace.resolve("libkraft.kso")
        val uname = workspace.resolve("uname.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/lib/libk16rt.kso",
            k16Runtime.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/lib/libkraft.kso",
            kraftRuntime.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/uname.kx",
            uname.toString(),
        )

        val kraftBytes = kraftRuntime.readBytes()
        assertTrue(kraftBytes.size > 112, "bundled /lib/libkraft.kso should be a non-empty K16E shared object")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            kraftBytes.copyOfRange(0, 4),
        )
        assertEquals(7, kraftBytes.u16Le(offset = 4), "bundled libkraft must use shareable K16E v7")
        assertEquals(4, kraftBytes.u32Le(offset = 24), "bundled libkraft must use K16E abi kind shared-object")
        assertEquals(0, kraftBytes.u32Le(offset = 88), "bundled shareable libkraft should not need relocations")
        assertTrue(kraftBytes.u32Le(offset = 48) >= kraftBytes.u32Le(offset = 44), "bundled libkraft readonly memory must cover readonly file bytes")
        val kraftMetadata = kraftBytes.decodeToString()
        listOf(
            "kraft_sys_open",
            "kraft_sys_read",
            "kraft_sys_write",
            "kraft_sys_close",
            "kraft_sys_read_dir",
            "kraft_sys_stat",
            "kraft_sys_rename",
            "kraft_sys_mkdir",
            "kraft_sys_rmdir",
            "kraft_sys_unlink",
            "kraft_sys_sbrk",
            "kraft_sys_exit",
            "kraft_sys_game_ticks",
        ).forEach { symbol ->
            assertTrue(
                kraftMetadata.contains(symbol),
                "bundled libkraft should export $symbol",
            )
        }
        listOf("kraft_write_all", "kraft_exit", "__k16_syscall1", "__k16_syscall3").forEach { symbol ->
            assertFalse(
                kraftMetadata.contains(symbol),
                "bundled libkraft should not export implementation symbol $symbol",
            )
        }

        val unameBytes = uname.readBytes()
        assertEquals(5, unameBytes.u16Le(offset = 4), "bundled /bin/uname.kx should use imported dynamic K16E v5")
        val unameMetadata = unameBytes.decodeToString()
        assertTrue(
            unameMetadata.contains("libkraft.kso"),
            "uname should declare libkraft.kso as a needed library",
        )
        listOf("kraft_sys_write").forEach { symbol ->
            assertTrue(
                unameMetadata.contains(symbol),
                "uname should import $symbol from libkraft",
            )
        }
        listOf("kraft_write_all", "kraft_exit").forEach { symbol ->
            assertFalse(
                unameMetadata.contains(symbol),
                "uname should not import prefixed symbol $symbol",
            )
        }
    }

    @Test
    fun bundledK16DevelopmentStorage0ExcludesRustUserlandProofPrograms() {
        val workspace = createTempDirectory("k16-dev-storage-layout-test-")
        val storage0 = workspace.resolve("storage0-dev.kv")
        val root = workspace.resolve("root.kfs")
        val allocTest = workspace.resolve("alloc-test.kx")
        val procTest = workspace.resolve("proc-test.kx")
        val runtimeImportTest = workspace.resolve("runtime-import-test.kx")
        val hostedCat = workspace.resolve("hosted-cat.kx")
        val hostedHello = workspace.resolve("hosted-hello.kx")
        storage0.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/alloc-test.kx",
            allocTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/proc-test.kx",
            procTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/runtime-import-test.kx",
            runtimeImportTest.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/hosted-cat.kx",
            hostedCat.toString(),
        )
        runK16ToolExpectFailure(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/hosted-hello.kx",
            hostedHello.toString(),
        )
    }

    @Test
    fun bundledK16SharedKraftUnameRunsThroughKernelLoader() {
        val workspace = createTempDirectory("k16-shared-kraft-uname-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(NativeK16ComputerControl.STATUS_READY, readyControl.status)

                runShellCommand(runtime, "uname")
                val terminal = terminalText(runtime.machineSnapshot())

                assertTrue(
                    terminal.contains("uname") && terminal.contains("K16"),
                    "uname should run through libkraft imports and print K16; terminal: $terminal",
                )
                assertFalse(
                    terminal.contains("ERR RUN"),
                    "uname should not fail during libkraft import resolution; terminal: $terminal",
                )
            }
    }

    @Test
    fun bundledK16RuntimeValidatesAndAttachesHostReadOnlyStorage1() {
        val workspace = createTempDirectory("k16-static-storage1-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        val missingStorage1Path = workspace.resolve("missing-storage1.kv")
        val invalidStorage1Path = workspace.resolve("invalid-storage1.kv")
        val storage1Path = workspace.resolve("storage1.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        invalidStorage1Path.writeBytes("not a K16 volume".encodeToByteArray())
        storage1Path.writeBytes(
            javaClass.classLoader
                .getResourceAsStream("firmware/sdk-fixture-v1.kv")
                ?.use { it.readBytes() }
                ?: error("test SDK fixture resource should exist"),
        )
        assertTrue(storage1Path.toFile().setReadOnly())

        assertFailsWith<IllegalArgumentException> {
            K16ComputerRuntimeFactory.createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                storage1 = K16StaticStorageAttachment(missingStorage1Path),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            K16ComputerRuntimeFactory.createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                storage1 = K16StaticStorageAttachment(invalidStorage1Path),
            )
        }
        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                storage1 = K16StaticStorageAttachment(storage1Path),
            ).use { runtime ->
                assertTrue(runtime.statsSnapshot().devices.any { it.base == 0x1000_0900L })
            }
    }

    @Test
    fun bundledK16SystemCatRunsThroughKernelLoader() {
        val workspace = createTempDirectory("k16-c-system-cat-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0-dev.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(NativeK16ComputerControl.STATUS_READY, readyControl.status)

                runShellCommand(runtime, "cat /etc/motd")
                var terminal = terminalText(runtime.machineSnapshot())
                var waitTurns = 0
                while (!terminal.contains("K16 FS OK") && !terminal.contains("ERR") && waitTurns < 64) {
                    val control = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
                    assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                    terminal = terminalText(runtime.machineSnapshot())
                    waitTurns += 1
                }

                assertTrue(
                    terminal.contains("cat /etc/motd") && terminal.contains("K16 FS OK"),
                    "production C cat should read /etc/motd through libkraft open/read/write; terminal: $terminal",
                )
                assertFalse(
                    terminal.contains("ERR RUN"),
                    "production C cat should not fail during libkraft import resolution; terminal: $terminal",
                )
            }
    }

    @Test
    fun bundledK16SystemWriteRunsThroughKernelLoader() {
        val workspace = createTempDirectory("k16-c-system-write-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0-dev.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(NativeK16ComputerControl.STATUS_READY, readyControl.status)

                runShellCommand(runtime, "write /tmp.txt hi")
                runShellCommand(runtime, "write --append /tmp.txt there")
                runShellCommand(runtime, "cat /tmp.txt")
                var terminal = terminalText(runtime.machineSnapshot())
                var waitTurns = 0
                while (!terminal.contains("hithere") && !terminal.contains("ERR") && waitTurns < 64) {
                    val control = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
                    assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                    terminal = terminalText(runtime.machineSnapshot())
                    waitTurns += 1
                }

                assertTrue(
                    terminal.contains("WROTE 2 /tmp.txt") &&
                        terminal.contains("WROTE 5 /tmp.txt") &&
                        terminal.contains("hithere"),
                    "production C write should create and append through libkraft open/write/close; terminal: $terminal",
                )
                assertFalse(
                    terminal.contains("ERR RUN"),
                    "production C write should not fail during libkraft import resolution; terminal: $terminal",
                )
            }
    }

    @Test
    fun k16RuntimeDeviceServerTicksAdvanceNativeTimer0Snapshot() {
        val workspace = createTempDirectory("k16-runtime-device-timer-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val device =
            K16RuntimeDevice(
                deviceId = 218,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
                serverThreadDispatcher = directServerThreadDispatcher,
            )

        try {
            device.turnOn()
            repeat(5) {
                device.serverTick()
            }

            val snapshot =
                requireNotNull(device.snapshotRuntimeState()) {
                    "K16 runtime device should expose a native machine snapshot while powered on"
                }

            assertEquals(5L, snapshotTimer0GameTicks(snapshot))
        } finally {
            device.close()
        }
    }

    @Test
    fun bundledK16SystemStorage0BootsRustKernel() {
        val workspace = createTempDirectory("k16-firmware-resource-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelRetainedConsoleVisible(runtime, control, debug)
            }
    }

    @Test
    fun bundledK16ShellUsesFdStdinAndStdoutInsteadOfDebugOutput() {
        val source = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertTrue(source.contains("STDOUT_FILENO"))
        assertTrue(source.contains("STDIN_FILENO"))
        assertTrue(source.contains("struct shell_state"))
        assertTrue(source.contains("char input[KRAFT_SHELL_INPUT_CAPACITY]"))
        assertTrue(source.contains("char cwd[KRAFT_MAX_SHELL_PATH_BYTES + 1]"))
        assertTrue(source.contains("read(STDIN_FILENO, read_buffer, sizeof(read_buffer))"))
        assertTrue(source.contains("dispatch_command("))
        assertTrue(source.contains("arg_paths"))
        assertTrue(source.contains("#define PROMPT \"K16> \""))
        assertFalse(source.contains("const HELP: &[u8]"), "shell should not carry built-in help text")
        assertFalse(source.contains("COMMAND_HELP"), "help should resolve through generic executable dispatch")
        assertTrue(source.contains("run_pwd(state)"))
        assertTrue(source.contains("run_cd(state, command->args"))
        assertTrue(source.contains("run_ticks()"))
        assertTrue(source.contains("run_exec(state, command->name, command->args, command->argc)"))
        assertTrue(source.contains("#define BIN_PREFIX \"/bin/\""))
        assertTrue(source.contains("#define PROGRAM_SUFFIX \".kx\""))
        assertFalse(source.contains("_exit(0)"))
        assertFalse(source.contains("debug::write_byte"))
    }

    @Test
    fun bundledK16ShellBatchesInteractiveInputAndEchoOutput() {
        val source = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertTrue(source.contains("#define KRAFT_SHELL_READ_BUFFER_BYTES 64"))
        assertTrue(source.contains("char read_buffer[KRAFT_SHELL_READ_BUFFER_BYTES]"))
        assertTrue(source.contains("char echo_buffer[KRAFT_SHELL_READ_BUFFER_BYTES]"))
        assertTrue(source.contains("flush_echo_run(echo_buffer, &echo_len)"))
        assertFalse(
            source.contains("write_all(STDOUT_FILENO, &byte, 1)"),
            "shell printable echo should batch adjacent bytes instead of forcing one syscall per character",
        )
        assertFalse(
            source.contains("char read_buffer[1]"),
            "shell should let stdin drain more than one queued keyboard byte per syscall",
        )
    }

    @Test
    fun k16KernelStdinCopiesQueuedKeyboardBytesToUserInBatches() {
        val source = Path.of("../../../guest/kraftos/kernel/src/stdin.rs").readText()

        assertTrue(source.contains("const STDIN_READ_CHUNK_BYTES: usize = 64;"))
        assertTrue(source.contains("let mut bytes = [0_u8; STDIN_READ_CHUNK_BYTES];"))
        assertTrue(source.contains("user_buffer::copy_to_user(ptr, &bytes[..copied as usize])?;"))
        assertFalse(
            source.contains("user_buffer::copy_to_user(ptr + copied, &bytes)?;"),
            "stdin should avoid one MMU0 copy/yield per queued keyboard byte",
        )
    }

    @Test
    fun bundledK16ShellReadsKeyboardInputThroughFdStdin() {
        val workspace = createTempDirectory("k16-init-stdin-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    readyControl.status,
                    "shell should wait for stdin after prompt; control: $readyControl; terminal: ${terminalText(runtime.machineSnapshot())}",
                )

                for (byte in "echo abc\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                val afterInputControl = continueUntilTerminalText(runtime, "abc", readyControl)
                val terminal = terminalText(runtime.machineSnapshot())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertTrue(
                    terminal.contains("K16> echo abc") && terminal.contains("abc"),
                    "shell should read stdin bytes and dispatch echo through stdout; terminal: $terminal",
                )
            }
    }

    @Test
    fun bundledK16ShellTicksCommandPrintsTimer0GameTicks() {
        val workspace = createTempDirectory("k16-shell-ticks-command-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(NativeK16ComputerControl.STATUS_READY, readyControl.status)
                runShellCommand(runtime, "clear")

                val ticksBeforeCommand = snapshotTimer0GameTicks(runtime.machineSnapshot())
                runShellCommand(runtime, "ticks")
                val expectedTicks = ticksBeforeCommand + 1
                assertEquals(
                    expectedTicks,
                    snapshotTimer0GameTicks(runtime.machineSnapshot()),
                    "timer0 game ticks should only advance by the command server tick",
                )
                val terminal = terminalText(runtime.machineSnapshot())
                val actualTicks =
                    Regex("""TICKS ([0-9]+)""")
                        .find(terminal)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                        ?: error("ticks command should print decimal timer0 ticks; terminal: $terminal")

                assertEquals(
                    expectedTicks,
                    actualTicks,
                    "ticks command should print timer0 game ticks from K16SNAP; terminal: $terminal",
                )
            }
    }

    @Test
    fun bundledK16RuntimeRejectsHighTimerSnapshotResumeWithoutRetainedGpuState() {
        val workspace = createTempDirectory("k16-shell-ticks-u64-command-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        val storage1Path = workspace.resolve("storage1.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        storage1Path.writeBytes(
            javaClass.classLoader
                .getResourceAsStream("firmware/sdk-fixture-v1.kv")
                ?.use { it.readBytes() }
                ?: error("test SDK fixture resource should exist"),
        )

        val bootedSnapshot =
            K16ComputerRuntimeFactory
                .createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                ).use { runtime ->
                    val readyControl = runUntilTerminalText(runtime, "K16> ")
                    assertEquals(NativeK16ComputerControl.STATUS_READY, readyControl.status)
                    runtime.machineSnapshot()
                }
        val restoredGameTicks = 0x0000_0001_0000_002aL
        val highTimerSnapshot = snapshotWithTimer0GameTicks(bootedSnapshot, restoredGameTicks)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    snapshot = highTimerSnapshot,
                    storage1 = K16StaticStorageAttachment(storage1Path),
                )
            }
        assertTrue(failure.message.orEmpty().contains("cannot preserve retained gpu0 state"))
    }

    @Test
    fun bundledK16KernelLaunchesInitProgram() {
        val workspace = createTempDirectory("k16-init-launch-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "init should remain ready while waiting for fd stdin; panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelRetainedConsoleVisible(runtime, control, debug)
            }
    }

    @Test
    fun bundledK16SystemStorage0ReachesGpuConsoleWithDefaultRuntimeTicks() {
        val workspace = createTempDirectory("k16-firmware-default-tick-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                var control = runRuntimeServerTick(runtime)

                var tick = 1
                while (tick < 24 && control.status != NativeK16ComputerControl.STATUS_READY) {
                    control = runRuntimeServerTick(runtime)
                    tick += 1
                }

                val debug = runtime.outputSnapshot().decodeToString()
                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "default runtime ticks should boot through /bin/init.kx; tick: $tick, panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKraftOsRetainedConsole(retainedState(runtime))
            }
    }

    @Test
    fun k16KernelConsoleDoesNotUseDisplay0() {
        val kernelSourceDir = Path.of("../../../guest/kraftos/kernel/src")
        val checkedFiles =
            listOf(
                "main.rs",
                "console.rs",
                "gpu.rs",
                "font.rs",
                "memory_layout.rs",
                "terminal.rs",
                "terminal_render.rs",
            )

        for (fileName in checkedFiles) {
            val source = kernelSourceDir.resolve(fileName).readText()
            assertFalse(
                source.contains("display0"),
                "$fileName must keep the kernel console path gpu0-only",
            )
        }
    }

    @Test
    fun k16BiosDoesNotUseDisplay0() {
        val biosSource = Path.of("../../../guest/firmware/bios/bios.c").readText()

        assertFalse(biosSource.contains("display0"), "K16 BIOS must render through gpu0 only")
        assertTrue(biosSource.contains("GPU_SUBMIT"), "K16 BIOS should submit retained gpu0 transactions")
        assertTrue(biosSource.contains("GPU_DEVICE_ABI_VERSION_VALUE 2u"))
        assertTrue(biosSource.contains("#define BIOS_BOOT_PARTITION \"BOOT\""), "K16 BIOS should name the BOOT KFS partition")
        assertTrue(biosSource.contains("#define BIOS_BOOT_DIR \"boot\""), "K16 BIOS should name the boot directory")
        assertTrue(biosSource.contains("#define BIOS_BOOTLOADER_FILE \"loader.kb\""), "K16 BIOS should name the bootloader K16E file")
        assertTrue(biosSource.contains("load_k16e_from_storage0"), "K16 BIOS should keep the storage boot path")
    }

    @Test
    fun k16KernelConsoleKeepsCellGridAndScrollsOnOverflow() {
        val kernelSourceDir = Path.of("../../../guest/kraftos/kernel/src")
        val consoleSource = kernelSourceDir.resolve("console.rs").readText()
        val memoryLayoutSource = kernelSourceDir.resolve("memory_layout.rs").readText()
        val terminalSource = kernelSourceDir.resolve("terminal.rs").readText()
        val terminalRenderSource = kernelSourceDir.resolve("terminal_render.rs").readText()

        assertTrue(consoleSource.contains("use crate::terminal;"), "console facade should delegate to terminal state")
        assertFalse(consoleSource.contains("static mut CURSOR_X:"), "console facade must not own cursor state")
        assertFalse(consoleSource.contains("static mut GLYPH_BUFFER:"), "console facade must not own glyph buffers")
        assertFalse(consoleSource.contains("fn render_glyph("), "console facade must not rasterize glyphs")
        assertFalse(consoleSource.contains("fn blit_glyph("), "console facade must not blit glyphs")
        assertFalse(consoleSource.contains("const CELLS_ADDR:"), "console facade must not own guest cell storage")

        assertTrue(terminalSource.contains("const CELLS_ADDR:"), "terminal should keep guest cell state")
        assertTrue(
            terminalSource.contains("memory_layout::TERMINAL_CELLS_ADDR"),
            "terminal should use the shared kernel memory layout for guest cell storage",
        )
        assertTrue(
            memoryLayoutSource.contains("pub const TERMINAL_CELLS_ADDR: u32 = 0x0000_3000;"),
            "terminal cells should live below the kernel image instead of overlapping program memory",
        )
        assertFalse(
            memoryLayoutSource.contains("pub const TERMINAL_CELLS_ADDR: u32 = 0x0000_8000;"),
            "terminal cells must not overlap the program load address",
        )
        assertTrue(terminalSource.contains("static mut CURSOR_X:"), "terminal should own cursor state")
        assertTrue(terminalSource.contains("fn write_cell("), "terminal should write cells into guest RAM")
        assertTrue(terminalSource.contains("static mut ROW_HEAD:"), "terminal should own the circular row head")
        assertTrue(terminalSource.contains("fn scroll_up("), "terminal should keep a bottom-overflow boundary")
        assertTrue(
            terminalSource.contains("ROW_HEAD = (ROW_HEAD + 1) % ROWS;"),
            "bottom overflow should rotate guest cell rows without copying the whole grid",
        )
        assertTrue(
            terminalSource.contains("terminal_render::scroll_up();"),
            "bottom overflow should rotate the retained instance presentation",
        )
        assertFalse(
            terminalSource.contains("repaint_all_cells();"),
            "bottom overflow must not repaint the whole terminal on every scroll",
        )
        assertFalse(
            terminalSource.contains("else {\n            CURSOR_Y = 0;\n        }"),
            "bottom overflow must not wrap to row zero",
        )

        assertTrue(terminalSource.contains("fn write_printable_run("), "terminal should batch printable runs")
        assertTrue(
            terminalSource.contains("terminal_render::set_cell("),
            "terminal should update retained instances while mutating guest cell state",
        )
        assertTrue(terminalRenderSource.contains("struct TerminalRenderState"))
        assertTrue(terminalRenderSource.contains("instances: [MaskInstance; INSTANCE_COUNT]"))
        assertTrue(
            terminalRenderSource.contains("patch_mask_instances("),
            "terminal renderer should patch retained mask instances",
        )
        assertFalse(
            terminalRenderSource.contains("blit_mono_buffer("),
            "terminal renderer must not retain the removed raster blit path",
        )
        assertTrue(
            terminalRenderSource.contains("pub fn scroll_up()"),
            "terminal renderer should expose a retained scroll primitive",
        )
        assertTrue(
            terminalRenderSource.contains("self.row_head = (self.row_head + 1) % ROWS;"),
            "terminal renderer should rotate the retained instance rows",
        )
        assertTrue(
            terminalRenderSource.contains("replace_draw_list("),
            "terminal renderer should publish the new logical row order",
        )
    }

    @Test
    fun k16KernelTerminalDefinesReadableByteSemantics() {
        val terminalSource = Path.of("../../../guest/kraftos/kernel/src/terminal.rs").readText()

        assertTrue(
            terminalSource.contains("pub fn clear() {\n    clear_terminal();\n}"),
            "terminal public clear should delegate to the named clear operation",
        )
        assertTrue(
            terminalSource.contains("b'\\n' => move_to_next_line(),"),
            "newline byte should move to the next terminal line",
        )
        assertTrue(
            terminalSource.contains("b'\\r' => move_to_column_start(),"),
            "carriage return byte should move to column zero",
        )
        assertTrue(
            terminalSource.contains("b'\\x0c' => clear_terminal(),"),
            "form feed should let userland stdout request a terminal clear",
        )
        assertTrue(
            terminalSource.contains("b'\\x08' => erase_previous_cell(),"),
            "backspace byte should erase the previous visible cell",
        )
        assertTrue(
            terminalSource.contains("b'\\t' => write_tab_spaces(),"),
            "tab byte should expand to terminal spaces",
        )
        assertTrue(
            terminalSource.contains("0x20..=0x7e => put_printable_byte(byte),"),
            "printable ASCII bytes should flow through the printable byte handler",
        )
    }

    @Test
    fun k16KernelReadSyscallUsesKeyboardInputPath() {
        val kernelSourceDir = Path.of("../../../guest/kraftos/kernel/src")
        val syscallSource = kernelSourceDir.resolve("syscall.rs").readText()
        val stdinSource = kernelSourceDir.resolve("stdin.rs").readText()
        val mainSource = kernelSourceDir.resolve("main.rs").readText()

        assertTrue(mainSource.contains("mod stdin;"), "kernel should register fd stdin input")
        assertTrue(syscallSource.contains("abi_syscall::READ"), "syscall dispatch should handle fd reads")
        assertTrue(syscallSource.contains("abi_syscall::STAT"), "syscall dispatch should handle path metadata")
        assertTrue(
            syscallSource.contains("fs::stat_root_path(path)"),
            "STAT(path, len, out) should delegate to the ROOT/KFS metadata path",
        )
        assertTrue(
            syscallSource.contains("stdin::read(ptr, len)"),
            "READ(FD_STDIN, ptr, len) should delegate to the stdin input path",
        )
        assertTrue(stdinSource.contains("keyboard0::EVENT_CHAR"), "stdin should consume keyboard character events")
        assertTrue(stdinSource.contains("keyboard0::EVENT_PASTE_BYTE"), "stdin should consume paste byte events")
        assertTrue(stdinSource.contains("k16_rt::wait_once()"), "blocking stdin reads should wait for host input without nesting syscalls")
    }

    @Test
    fun kraftOsFilesystemUsesKfsSourceLayoutAndMagic() {
        val kernelSourceDir = Path.of("../../../guest/kraftos/kernel/src")
        val bootChainSource = Path.of("../../../guest/firmware/boot-chain/boot_chain.c").readText()
        val kfsModSource = kernelSourceDir.resolve("kfs/mod.rs").readText()
        val superblockSource = kernelSourceDir.resolve("kfs/superblock.rs").readText()

        assertTrue(Files.isDirectory(kernelSourceDir.resolve("kfs")), "kernel filesystem code should live under kfs/")
        assertTrue(kernelSourceDir.resolve("kfs/cache.rs").toFile().isFile, "KFS cache module should be explicit")
        assertTrue(kernelSourceDir.resolve("kfs/root.rs").toFile().isFile, "KFS root module should be explicit")
        assertFalse(kernelSourceDir.resolve("kfs/storage.rs").toFile().exists(), "KFS storage facade should be removed")
        assertFalse(kfsModSource.contains("pub mod storage;"), "KFS storage module should not be exported")
        assertTrue(kernelSourceDir.resolve("kfs/superblock.rs").toFile().isFile, "KFS superblock module should own KFS magic")
        assertFalse(kernelSourceDir.resolve("storage.rs").toFile().exists(), "top-level storage module should not own KFS code")
        assertFalse(kernelSourceDir.resolve("k16fs_cache.rs").toFile().exists(), "old k16fs cache module should be removed")
        assertFalse(kernelSourceDir.resolve("k16fs_root.rs").toFile().exists(), "old k16fs root module should be removed")
        assertTrue(bootChainSource.contains("\"KFS\\0\\0\""), "boot-chain should require the KFS disk magic")
        assertFalse(bootChainSource.contains("\"K16FS\""), "boot-chain should not accept the old K16FS magic")
        assertTrue(superblockSource.contains("KFS_MAGIC"), "kernel superblock decoder should name the KFS magic")
        assertFalse(superblockSource.contains("K16FS_MAGIC"), "kernel superblock decoder should not keep the old K16FS magic")
    }

    @Test
    fun kraftOsFilesystemPublicTypesAreNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        assertTrue(kernelKfsDir.resolve("error.rs").toFile().isFile, "KFS errors should have an explicit module")
        assertTrue(kernelKfsDir.resolve("types.rs").toFile().isFile, "KFS public types should have an explicit module")
        assertTrue(modSource.contains("pub mod error;"), "KFS error module should be exported")
        assertTrue(modSource.contains("pub mod types;"), "KFS types module should be exported")
        assertFalse(storageSource.contains("pub struct FileMetadata"), "FileMetadata should not be owned by storage.rs")
        assertFalse(storageSource.contains("pub enum FileReadProfileKind"), "read profiling types should not be owned by storage.rs")
        assertFalse(storageSource.contains("pub enum PathKind"), "path metadata types should not be owned by storage.rs")
        assertFalse(storageSource.contains("pub struct StorageError"), "StorageError should not be owned by storage.rs")
        assertFalse(storageSource.contains("pub trait DirectoryListingSink"), "directory listing sink types should not be owned by storage.rs")
    }

    @Test
    fun kraftOsFilesystemBlockIoIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val volumeSource = kernelKfsDir.resolve("volume.rs").readText()
        val blockCacheSource = kernelKfsDir.resolve("block_cache.rs").readText()
        val mountSource = kernelKfsDir.resolve("mount.rs").readText()

        val blockIoPath = kernelKfsDir.resolve("block_io.rs")
        assertTrue(blockIoPath.toFile().isFile, "KFS block I/O should have an explicit module")
        val blockIoSource = blockIoPath.readText()
        assertTrue(modSource.contains("pub mod block_io;"), "KFS block I/O module should be exported")
        assertTrue(modSource.contains("pub mod block_cache;"), "KFS block cache module should be exported")
        assertTrue(modSource.contains("pub mod volume;"), "KFS volume module should be exported")
        assertTrue(blockIoSource.contains("pub const SCRATCH_ADDR"), "block_io.rs should own the scratch block address")
        assertTrue(blockIoSource.contains("pub const BLOCK_SIZE"), "block_io.rs should own the block size")
        assertTrue(
            blockIoSource.contains("pub(crate) const KFS_BLOCK_CACHE_SLOTS: usize = 16;"),
            "block_io.rs should expose the bounded per-VM block cache slot budget",
        )
        assertTrue(blockCacheSource.contains("pub struct KfsBlockCache<const SLOTS: usize>"), "block_cache.rs should own reusable block cache state")
        assertTrue(volumeSource.contains("pub(crate) block_cache: KfsBlockCache<KFS_BLOCK_CACHE_SLOTS>"), "each KFS volume should own an independent block cache")
        assertFalse(blockCacheSource.contains("static "), "block cache state should not be global")
        assertFalse(blockCacheSource.contains("UnsafeCell"), "block cache state should not use global interior mutability")
        assertTrue(blockIoSource.contains("pub(crate) unsafe fn read_fs_block("), "block_io.rs should own single block reads")
        assertTrue(blockIoSource.contains("pub(crate) unsafe fn read_fs_blocks_to_ram("), "block_io.rs should own batched block reads")
        assertTrue(blockIoSource.contains("pub(crate) unsafe fn write_fs_block("), "block_io.rs should own block writes")
        assertTrue(blockIoSource.contains("pub(crate) unsafe fn clear_scratch_block("), "block_io.rs should own scratch clearing")
        assertTrue(blockIoSource.contains("pub(crate) fn scratch_u8("), "block_io.rs should own scratch byte reads")
        assertTrue(blockIoSource.contains("pub(crate) fn scratch_u32("), "block_io.rs should own scratch word reads")
        assertTrue(blockIoSource.contains("pub(crate) unsafe fn copy_ram_to_ram("), "block_io.rs should own RAM copies")
        assertTrue(blockIoSource.contains("volume: &mut crate::kfs::volume::KfsVolume"), "block I/O should be scoped to an explicit volume")
        assertTrue(blockIoSource.contains("volume.block_cache"), "block I/O should use the selected volume cache")
        assertTrue(blockIoSource.contains("volume.filesystem"), "block I/O should use the selected volume mount state")
        assertFalse(storageSource.contains("pub const SCRATCH_ADDR"), "storage.rs should not own the scratch block address")
        assertFalse(storageSource.contains("pub const BLOCK_SIZE"), "storage.rs should not own the block size")
        assertFalse(storageSource.contains("struct KfsBlockCache"), "storage.rs should not own the block cache state")
        assertFalse(storageSource.contains("pub(crate) unsafe fn read_fs_block("), "storage.rs should not own single block reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn read_fs_blocks_to_ram("), "storage.rs should not own batched block reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn write_fs_block("), "storage.rs should not own block writes")
        assertFalse(storageSource.contains("pub(crate) unsafe fn clear_scratch_block("), "storage.rs should not own scratch clearing")
        assertFalse(storageSource.contains("pub(crate) fn scratch_u8("), "storage.rs should not own scratch byte reads")
        assertFalse(storageSource.contains("pub(crate) fn scratch_u32("), "storage.rs should not own scratch word reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn copy_ram_to_ram("), "storage.rs should not own RAM copies")
        assertFalse(storageSource.contains("crate::kfs::block_io::read_storage_block("), "storage.rs should not read storage blocks directly")
        assertFalse(storageSource.contains("crate::kfs::block_io::read_fs_block("), "storage.rs should not read filesystem blocks directly")
        assertFalse(storageSource.contains("crate::kfs::block_io::invalidate_block_cache("), "storage.rs should not invalidate the block cache directly")
        assertTrue(mountSource.contains("crate::kfs::block_io::read_storage_block(volume,"), "mount.rs should pass its volume to partition reads")
        assertTrue(mountSource.contains("crate::kfs::block_io::read_fs_block(volume,"), "mount.rs should pass its volume to filesystem reads")
        assertTrue(mountSource.contains("crate::kfs::block_io::invalidate_block_cache(volume)"), "mount.rs should invalidate only the selected volume cache")
    }

    @Test
    fun kraftOsFilesystemSelectedInodeStateIsNotOwnedByStorageModule() {
        val kernelDir = Path.of("../../../guest/kraftos/kernel/src")
        val kernelKfsDir = kernelDir.resolve("kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val volumeSource = kernelKfsDir.resolve("volume.rs").readText()
        val rootSource = kernelKfsDir.resolve("root.rs").readText()
        val inodeSource = kernelKfsDir.resolve("inode.rs").readText()

        val selectedInodePath = kernelKfsDir.resolve("selected_inode.rs")
        assertTrue(selectedInodePath.toFile().isFile, "KFS selected inode state should have an explicit module")
        val selectedInodeSource = selectedInodePath.readText()
        assertTrue(modSource.contains("pub mod selected_inode;"), "KFS selected inode module should be exported")
        assertTrue(selectedInodeSource.contains("pub(crate) const INODE_STATE_REGULAR"), "selected_inode.rs should own inode state tags")
        assertTrue(selectedInodeSource.contains("pub struct SelectedInodeState"), "selected_inode.rs should own an instance state type")
        assertTrue(selectedInodeSource.contains("pub const fn state(&self)"), "selected inode state should expose inode state reads")
        assertTrue(selectedInodeSource.contains("pub const fn extent_start_block(&self"), "selected inode state should expose extent reads")
        assertTrue(selectedInodeSource.contains("pub fn path_metadata(&self)"), "selected inode state should own path metadata projection")
        assertTrue(selectedInodeSource.contains("pub fn file_metadata(&self)"), "selected inode state should own file metadata projection")
        assertTrue(selectedInodeSource.contains("pub fn metadata_for_cache("), "selected inode state should own cached metadata projection")
        assertTrue(selectedInodeSource.contains("pub unsafe fn select_file_metadata("), "selected_inode.rs should own metadata-backed inode selection")
        assertTrue(selectedInodeSource.contains("pub fn store_loaded_inode("), "selected inode state should own selected inode state writes")
        assertTrue(volumeSource.contains("pub(crate) selected_inode: SelectedInodeState"), "each KFS volume should own independent selected-inode state")
        assertFalse(selectedInodeSource.contains("static "), "selected inode state should not be global")
        assertFalse(selectedInodeSource.contains("UnsafeCell"), "selected inode state should not use global interior mutability")
        assertFalse(storageSource.contains("pub(crate) const INODE_STATE_REGULAR"), "storage.rs should not own inode state tags")
        assertFalse(storageSource.contains("pub(crate) unsafe fn selected_inode_state("), "storage.rs should not own selected inode state reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn selected_inode_extent_start_block("), "storage.rs should not own selected inode extent reads")
        assertFalse(storageSource.contains("pub unsafe fn selected_path_metadata("), "storage.rs should not own selected path metadata")
        assertFalse(storageSource.contains("pub unsafe fn selected_file_metadata("), "storage.rs should not own selected file metadata")
        assertFalse(storageSource.contains("pub unsafe fn selected_metadata_for_cache("), "storage.rs should not own cached metadata projection")
        assertFalse(storageSource.contains("pub unsafe fn select_file_metadata("), "storage.rs should not own metadata-backed inode selection")
        assertFalse(storageSource.contains("SelectedInodeState"), "storage.rs should not own selected inode state")
        assertTrue(inodeSource.contains("volume.selected_inode.store_loaded_inode("), "inode.rs should update the selected inode of its volume")
        assertFalse(storageSource.contains("crate::kfs::selected_inode::selected_path_metadata("), "storage.rs should not own stat metadata projection")
        assertTrue(rootSource.contains("pub unsafe fn stat_path("), "root.rs should own mounted stat path lookup")
        assertTrue(rootSource.contains("self.resolve_path(path)"), "root.rs stat should use cached root path resolution")
        assertTrue(rootSource.contains("self.selected_inode.file_metadata()"), "root.rs should project file metadata from its volume state")
    }

    @Test
    fun kraftOsFilesystemMountedStateIsNotOwnedByStorageModule() {
        val kernelDir = Path.of("../../../guest/kraftos/kernel/src")
        val kernelKfsDir = kernelDir.resolve("kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val volumeSource = kernelKfsDir.resolve("volume.rs").readText()
        val blockIoSource = kernelKfsDir.resolve("block_io.rs").readText()
        val allocationSource = kernelKfsDir.resolve("allocation.rs").readText()
        val mountSource = kernelKfsDir.resolve("mount.rs").readText()
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs").readText()
        val pathSource = kernelKfsDir.resolve("path.rs").readText()
        val rootSource = kernelKfsDir.resolve("root.rs").readText()

        val filesystemStatePath = kernelKfsDir.resolve("filesystem_state.rs")
        assertTrue(filesystemStatePath.toFile().isFile, "KFS mounted filesystem state should have an explicit module")
        val filesystemStateSource = filesystemStatePath.readText()
        assertTrue(modSource.contains("pub mod filesystem_state;"), "KFS filesystem state module should be exported")
        assertTrue(filesystemStateSource.contains("pub struct KfsFilesystemState"), "filesystem_state.rs should own an instance state type")
        assertTrue(filesystemStateSource.contains("partition: KfsPartition"), "filesystem state should own its partition")
        assertTrue(filesystemStateSource.contains("superblock: KfsSuperblock"), "filesystem state should own its superblock")
        assertTrue(filesystemStateSource.contains("pub const fn partition_start_lba(&self)"), "filesystem state should expose partition start reads")
        assertTrue(filesystemStateSource.contains("pub const fn partition_block_count(&self)"), "filesystem state should expose partition size reads")
        assertTrue(filesystemStateSource.contains("pub const fn superblock_total_blocks(&self)"), "filesystem state should expose total block reads")
        assertTrue(filesystemStateSource.contains("pub const fn superblock_inode_table_start_block(&self)"), "filesystem state should expose inode table reads")
        assertTrue(filesystemStateSource.contains("pub const fn root_inode_id(&self)"), "filesystem state should expose root inode reads")
        assertTrue(filesystemStateSource.contains("pub fn store_partition(&mut self"), "filesystem state should own mounted partition writes")
        assertTrue(filesystemStateSource.contains("pub fn store_superblock(&mut self"), "filesystem state should own mounted superblock writes")
        assertTrue(volumeSource.contains("pub(crate) filesystem: KfsFilesystemState"), "each KFS volume should own independent filesystem state")
        assertTrue(volumeSource.contains("pub(crate) mounted: Option<MountedKfs>"), "each KFS volume should own independent mount state")
        assertFalse(filesystemStateSource.contains("static "), "filesystem state should not be global")
        assertFalse(filesystemStateSource.contains("UnsafeCell"), "filesystem state should not use global interior mutability")
        assertTrue(mountSource.contains("pub unsafe fn mount_root_partition_superblock("), "mount.rs should own root partition mounting")
        assertTrue(mountSource.contains("pub unsafe fn read_root_partition_superblock("), "mount.rs should own root partition superblock reads")
        assertTrue(mountSource.contains("pub(crate) unsafe fn read_partition("), "mount.rs should own partition reads")
        assertTrue(mountSource.contains("pub(crate) unsafe fn read_superblock("), "mount.rs should own superblock reads")
        assertTrue(mountSource.contains("volume.filesystem.store_partition(partition)"), "mount.rs should update the selected volume partition")
        assertTrue(mountSource.contains("volume.filesystem.store_superblock(superblock)"), "mount.rs should update the selected volume superblock")
        assertFalse(storageSource.contains("const STATE_PARTITION_START_LBA"), "storage.rs should not own partition state slots")
        assertFalse(storageSource.contains("const STATE_SUPERBLOCK_TOTAL_BLOCKS"), "storage.rs should not own superblock state slots")
        assertFalse(storageSource.contains("pub(crate) unsafe fn partition_start_lba("), "storage.rs should not own partition start reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn partition_block_count("), "storage.rs should not own partition size reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn superblock_total_blocks("), "storage.rs should not own total block reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn superblock_inode_table_start_block("), "storage.rs should not own inode table reads")
        assertFalse(storageSource.contains("pub unsafe fn root_inode_id("), "storage.rs should not own root inode reads")
        assertFalse(storageSource.contains("pub unsafe fn mount_root_partition_superblock("), "storage.rs should not own root partition mounting")
        assertFalse(storageSource.contains("pub unsafe fn read_root_partition_superblock("), "storage.rs should not own root partition superblock reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn read_partition("), "storage.rs should not own partition reads")
        assertFalse(storageSource.contains("pub(crate) unsafe fn read_superblock("), "storage.rs should not own superblock reads")
        assertFalse(storageSource.contains("crate::kfs::filesystem_state::store_partition("), "storage.rs should not write mounted partition state directly")
        assertFalse(storageSource.contains("crate::kfs::filesystem_state::store_superblock("), "storage.rs should not write mounted superblock state directly")
        assertFalse(storageSource.contains("crate::kfs::mount::read_partition("), "storage.rs should not own partition read delegation")
        assertFalse(storageSource.contains("crate::kfs::mount::read_superblock("), "storage.rs should not own superblock read delegation")
        assertFalse(namespaceMutationSource.contains("mount::read_partition("), "namespace mutation should run under an already-mounted root context")
        assertFalse(namespaceMutationSource.contains("mount::read_superblock("), "namespace mutation should run under an already-mounted root context")
        assertFalse(namespaceMutationSource.contains("storage::read_partition("), "namespace mutation should not use storage-owned partition reads")
        assertTrue(rootSource.contains("impl crate::kfs::volume::KfsVolume"), "root operations should be methods on an explicit KFS volume")
        assertTrue(rootSource.contains("crate::kfs::mount::mount_root_partition_superblock(self,"), "root.rs should pass its volume to the mount owner")
        assertTrue(rootSource.contains("crate::kfs::mount::read_partition("), "root.rs should use the mount owner for direct storage0 file selection")
        assertTrue(rootSource.contains("crate::kfs::mount::read_superblock("), "root.rs should use the mount owner for direct storage0 file selection")
        assertTrue(blockIoSource.contains("volume.filesystem.partition_start_lba()"), "block_io.rs should use the selected volume filesystem state")
        assertTrue(allocationSource.contains("volume.filesystem.superblock_total_blocks()"), "allocation.rs should use the selected volume filesystem state")
        assertTrue(pathSource.contains("volume.filesystem.root_inode_id()"), "path.rs should use the selected volume root inode")
        assertTrue(rootSource.contains("self.filesystem.root_inode_id()"), "root.rs should use its volume root inode")
    }

    @Test
    fun kraftOsFilesystemInodeLayoutIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val allocationSource = kernelKfsDir.resolve("allocation.rs").readText()
        val directoryListingSource = kernelKfsDir.resolve("directory_listing.rs").readText()
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs").readText()
        val pathSource = kernelKfsDir.resolve("path.rs").readText()
        val selectedInodeSource = kernelKfsDir.resolve("selected_inode.rs").readText()
        val mountSource = kernelKfsDir.resolve("mount.rs").readText()

        val inodePath = kernelKfsDir.resolve("inode.rs")
        assertTrue(inodePath.toFile().isFile, "KFS inode layout should have an explicit module")
        val inodeSource = inodePath.readText()
        assertTrue(modSource.contains("pub mod inode;"), "KFS inode module should be exported")
        assertTrue(inodeSource.contains("pub const KFS_INODE_SIZE"), "inode.rs should own inode record sizing")
        assertTrue(inodeSource.contains("pub fn locate_inode("), "inode.rs should own inode table addressing")
        assertTrue(inodeSource.contains("pub fn inode_capacity("), "inode.rs should own inode table capacity")
        assertTrue(inodeSource.contains("pub(crate) unsafe fn load_inode("), "inode.rs should own inode record loading")
        assertFalse(storageSource.contains("const KFS_INODE_SIZE"), "storage.rs should not own inode record sizing")
        assertFalse(storageSource.contains("pub(crate) unsafe fn read_inode("), "storage.rs should not own inode record loading")
        assertFalse(storageSource.contains("crate::kfs::inode::locate_inode("), "storage.rs should not decode inode records directly")
        assertFalse(storageSource.contains("crate::kfs::inode::load_inode("), "storage.rs should not load root inode directly")
        assertTrue(mountSource.contains("crate::kfs::inode::load_inode("), "mount.rs should use the inode owner for root inode loading")
        assertTrue(allocationSource.contains("inode::load_inode("), "allocation.rs should use the inode owner for inode scans")
        assertTrue(directoryListingSource.contains("inode::load_inode("), "directory listing should use the inode owner for child metadata")
        assertTrue(namespaceMutationSource.contains("inode::load_inode("), "namespace mutation should use the inode owner")
        assertTrue(pathSource.contains("inode::load_inode("), "path traversal should use the inode owner")
        assertTrue(selectedInodeSource.contains("crate::kfs::inode::load_inode("), "selected inode helpers should use the inode owner")
    }

    @Test
    fun kraftOsFilesystemInodeMutationIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val directoryMutationSource = kernelKfsDir.resolve("directory_mutation.rs").readText()
        val fileWriteSource = kernelKfsDir.resolve("file_write.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val mutationPath = kernelKfsDir.resolve("inode_mutation.rs")
        assertTrue(mutationPath.toFile().isFile, "KFS inode mutation should have an explicit module")
        val mutationSource = mutationPath.readText()
        assertTrue(modSource.contains("pub mod inode_mutation;"), "KFS inode mutation module should be exported")
        assertTrue(mutationSource.contains("pub unsafe fn encode_file_inode("), "inode_mutation.rs should own regular inode encoding")
        assertTrue(mutationSource.contains("pub unsafe fn encode_directory_inode("), "inode_mutation.rs should own directory inode encoding")
        assertTrue(mutationSource.contains("pub unsafe fn encode_deleted_file_inode("), "inode_mutation.rs should own deleted regular inode encoding")
        assertTrue(mutationSource.contains("pub unsafe fn encode_deleted_directory_inode("), "inode_mutation.rs should own deleted directory inode encoding")
        assertTrue(mutationSource.contains("pub unsafe fn encode_selected_inode_size("), "inode_mutation.rs should own selected inode size updates")
        assertTrue(mutationSource.contains("unsafe fn encode_inode("), "inode_mutation.rs should own inode record writes")
        assertFalse(storageSource.contains("unsafe fn encode_file_inode("), "storage.rs should not own regular inode encoding")
        assertFalse(storageSource.contains("unsafe fn encode_deleted_file_inode("), "storage.rs should not own deleted regular inode encoding")
        assertFalse(storageSource.contains("unsafe fn encode_deleted_directory_inode("), "storage.rs should not own deleted directory inode encoding")
        assertFalse(storageSource.contains("unsafe fn encode_selected_inode_size("), "storage.rs should not own selected inode size updates")
        assertFalse(storageSource.contains("unsafe fn encode_inode("), "storage.rs should not own inode record writes")
        assertTrue(fileWriteSource.contains("crate::kfs::inode_mutation::encode_file_inode("), "file_write.rs should use the inode mutation owner")
        assertTrue(directoryMutationSource.contains("crate::kfs::inode_mutation::encode_directory_inode("), "directory mutation should use the inode mutation owner")
    }

    @Test
    fun kraftOsFilesystemDirectoryLayoutIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val cacheSource = kernelKfsDir.resolve("cache.rs").readText()
        val listingSource = kernelKfsDir.resolve("directory_listing.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val directoryPath = kernelKfsDir.resolve("directory.rs")
        assertTrue(directoryPath.toFile().isFile, "KFS directory entry layout should have an explicit module")
        val directorySource = directoryPath.readText()
        assertTrue(modSource.contains("pub mod directory;"), "KFS directory module should be exported")
        assertTrue(directorySource.contains("pub const KFS_DIRECTORY_ENTRY_SIZE"), "directory.rs should own directory entry sizing")
        assertTrue(directorySource.contains("pub const KFS_MAX_NAME_BYTES"), "directory.rs should own directory name limits")
        assertTrue(directorySource.contains("pub fn decode_entry_header("), "directory.rs should own directory entry decode rules")
        assertTrue(directorySource.contains("pub fn encode_entry("), "directory.rs should own directory entry encode rules")
        assertFalse(storageSource.contains("const KFS_DIRECTORY_ENTRY_SIZE"), "storage.rs should not own directory entry sizing")
        assertFalse(storageSource.contains("const KFS_MAX_NAME_BYTES"), "storage.rs should not own directory name limits")
        assertFalse(cacheSource.contains("const KFS_MAX_NAME_BYTES"), "cache.rs should not duplicate directory name limits")
        assertTrue(cacheSource.contains("crate::kfs::directory::KFS_MAX_NAME_BYTES"), "cache.rs should use the directory owner for name limits")
        assertTrue(
            cacheSource.contains("pub(crate) const INODE_CACHE_SLOTS: usize = 32;"),
            "cache.rs should expose the bounded per-VM inode cache slot budget",
        )
        assertTrue(
            cacheSource.contains("pub(crate) const DIRECTORY_CACHE_SLOTS: usize = 32;"),
            "cache.rs should expose the bounded per-VM dentry cache slot budget",
        )
        assertTrue(listingSource.contains("crate::kfs::directory::decode_entry_header("), "directory_listing.rs should use the directory owner for entry decoding")
    }

    @Test
    fun kraftOsFilesystemBitmapLayoutIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val allocationSource = kernelKfsDir.resolve("allocation.rs").takeIf { it.toFile().isFile }?.readText().orEmpty()

        val bitmapPath = kernelKfsDir.resolve("bitmap.rs")
        assertTrue(bitmapPath.toFile().isFile, "KFS bitmap layout should have an explicit module")
        val bitmapSource = bitmapPath.readText()
        assertTrue(modSource.contains("pub mod bitmap;"), "KFS bitmap module should be exported")
        assertTrue(bitmapSource.contains("pub struct KfsBitmapLayout"), "bitmap.rs should own bitmap layout inputs")
        assertTrue(bitmapSource.contains("pub fn locate_block("), "bitmap.rs should own block-to-bitmap addressing")
        assertTrue(bitmapSource.contains("pub fn block_is_metadata("), "bitmap.rs should own metadata block classification")
        assertTrue(bitmapSource.contains("pub fn mark_byte_allocated("), "bitmap.rs should own allocated-bit mutation")
        assertTrue(bitmapSource.contains("pub fn mark_byte_free("), "bitmap.rs should own free-bit mutation")
        assertFalse(storageSource.contains("fn block_in_range("), "storage.rs should not own bitmap range classification")
        assertFalse(storageSource.contains("fn bitmap_block_scratch_marks_allocated("), "storage.rs should not own bitmap bit decoding")
        assertTrue(allocationSource.contains("crate::kfs::bitmap::locate_block("), "allocation.rs should use the bitmap owner for addressing")
    }

    @Test
    fun kraftOsFilesystemFilePlanningIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val fileWriteSource = kernelKfsDir.resolve("file_write.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val filePath = kernelKfsDir.resolve("file.rs")
        assertTrue(filePath.toFile().isFile, "KFS file extent planning should have an explicit module")
        val fileSource = filePath.readText()
        assertTrue(modSource.contains("pub mod file;"), "KFS file module should be exported")
        assertTrue(fileSource.contains("pub fn file_capacity_bytes("), "file.rs should own file capacity calculation")
        assertTrue(fileSource.contains("pub fn validate_read_range("), "file.rs should own read range validation")
        assertTrue(fileSource.contains("pub fn validate_write_range("), "file.rs should own write range validation")
        assertTrue(fileSource.contains("fn validate_extent("), "file.rs should own extent bounds validation")
        assertTrue(fileSource.contains("pub fn extent_overlap("), "file.rs should own extent overlap planning")
        assertTrue(fileSource.contains("pub fn plan_file_growth("), "file.rs should own file growth planning")
        assertFalse(storageSource.contains("fn file_capacity_bytes("), "storage.rs should not own file capacity calculation")
        assertFalse(storageSource.contains("fn div_ceil_u32("), "storage.rs should not own file growth rounding")
        assertTrue(fileWriteSource.contains("crate::kfs::file::extent_overlap("), "file_write.rs should use the file owner for range planning")
    }

    @Test
    fun kraftOsFilesystemOpenFileStateIsNotOwnedByFsTable() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val fsSource = Path.of("../../../guest/kraftos/kernel/src/fs.rs").readText()

        val openFilePath = kernelKfsDir.resolve("open_file.rs")
        assertTrue(openFilePath.toFile().isFile, "KFS open file state should have an explicit module")
        val openFileSource = openFilePath.readText()
        assertTrue(modSource.contains("pub mod open_file;"), "KFS open file module should be exported")
        assertTrue(openFileSource.contains("pub struct KfsOpenFile"), "open_file.rs should own KFS open file state")
        assertTrue(openFileSource.contains("pub fn regular_file("), "open_file.rs should own regular-file construction")
        assertTrue(openFileSource.contains("pub fn read_plan("), "open_file.rs should own read offset planning")
        assertTrue(openFileSource.contains("pub fn finish_read("), "open_file.rs should own read offset advancement")
        assertTrue(openFileSource.contains("pub fn write_plan("), "open_file.rs should own write offset planning")
        assertTrue(openFileSource.contains("pub fn finish_write("), "open_file.rs should own write metadata refresh and offset advancement")
        assertFalse(
            fsSource.contains("    metadata: FileMetadata,\n    offset: u32,"),
            "fs.rs fd table should not own KFS file metadata plus offsets",
        )
        assertTrue(fsSource.contains("crate::kfs::open_file::KfsOpenFile"), "fs.rs should use the KFS open-file owner")
    }

    @Test
    fun kraftOsFilesystemPathTraversalIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val rootSource = kernelKfsDir.resolve("root.rs").readText()

        val pathPath = kernelKfsDir.resolve("path.rs")
        assertTrue(pathPath.toFile().isFile, "KFS path traversal should have an explicit module")
        val pathSource = pathPath.readText()
        assertTrue(modSource.contains("pub mod path;"), "KFS path module should be exported")
        assertTrue(pathSource.contains("pub struct KfsDirectoryEntrySlot"), "path.rs should own directory slot lookup output")
        assertTrue(pathSource.contains("pub unsafe fn find_file_inode("), "path.rs should own regular file traversal")
        assertTrue(pathSource.contains("pub unsafe fn find_directory_inode("), "path.rs should own directory traversal")
        assertTrue(pathSource.contains("pub unsafe fn find_path_inode("), "path.rs should own generic path traversal")
        assertTrue(pathSource.contains("pub unsafe fn find_directory_entry_slot("), "path.rs should own directory entry slot lookup")
        assertFalse(storageSource.contains("unsafe fn find_file_inode("), "storage.rs should not own regular file traversal")
        assertFalse(storageSource.contains("unsafe fn find_directory_inode("), "storage.rs should not own directory traversal")
        assertFalse(storageSource.contains("unsafe fn find_path_inode("), "storage.rs should not own generic path traversal")
        assertFalse(storageSource.contains("unsafe fn find_directory_entry_slot("), "storage.rs should not own directory entry slot lookup")
        assertFalse(storageSource.contains("crate::kfs::path::find_file_inode("), "storage.rs should not own path traversal delegation")
        assertTrue(
            rootSource.contains("pub unsafe fn select_boot_or_test_file_from_storage0("),
            "root.rs should name direct storage0 file selection as a boot/test-only path",
        )
        assertFalse(
            rootSource.contains("pub unsafe fn select_file_from_storage0("),
            "root.rs should not expose generic direct storage0 file selection",
        )
        assertTrue(rootSource.contains("crate::kfs::path::find_file_inode("), "root.rs should use the path owner for direct storage0 file selection")
        assertFalse(rootSource.contains("pub unsafe fn open_file_from_storage0("), "root.rs should not expose direct storage0 selection as an open-file API")
        assertFalse(rootSource.contains("pub unsafe fn select_directory_inode("), "root.rs should not expose unused direct directory selection helpers")
        assertFalse(rootSource.contains("pub unsafe fn stat_path_from_storage0("), "root.rs should not expose unused direct stat helpers")
        assertFalse(rootSource.contains("pub unsafe fn selected_directory_entry_inode("), "root.rs directory lookup helper should stay private")
    }

    @Test
    fun kraftOsFilesystemDirectoryMutationIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val mutationPath = kernelKfsDir.resolve("directory_mutation.rs")
        assertTrue(mutationPath.toFile().isFile, "KFS directory mutation should have an explicit module")
        val mutationSource = mutationPath.readText()
        assertTrue(modSource.contains("pub mod directory_mutation;"), "KFS directory mutation module should be exported")
        assertTrue(mutationSource.contains("pub struct KfsDirectoryFreeSlot"), "directory_mutation.rs should own free-slot lookup output")
        assertTrue(mutationSource.contains("pub unsafe fn find_selected_directory_free_slot("), "directory_mutation.rs should own free-slot lookup")
        assertTrue(mutationSource.contains("pub unsafe fn grow_selected_directory_capacity("), "directory_mutation.rs should own directory growth")
        assertFalse(storageSource.contains("unsafe fn find_selected_directory_free_slot("), "storage.rs should not own directory free-slot lookup")
        assertFalse(storageSource.contains("unsafe fn grow_selected_directory_capacity("), "storage.rs should not own directory growth")
        assertFalse(storageSource.contains("STATE_DIRECTORY_SLOT_"), "storage.rs should not persist directory slot lookup through scratch state")
        assertTrue(namespaceMutationSource.contains("crate::kfs::directory_mutation::find_selected_directory_free_slot("), "namespace mutation should use the directory mutation owner")
    }

    @Test
    fun kraftOsFilesystemDirectoryEntryMutationIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val mutationSource = kernelKfsDir.resolve("directory_mutation.rs").readText()
        assertTrue(mutationSource.contains("pub unsafe fn encode_directory_entry_at("), "directory_mutation.rs should own live directory entry writes")
        assertTrue(mutationSource.contains("pub unsafe fn encode_deleted_directory_entry_at("), "directory_mutation.rs should own deleted directory entry writes")
        assertTrue(mutationSource.contains("crate::kfs::directory::encode_entry("), "directory mutation should use the directory record encoder")
        assertTrue(mutationSource.contains("crate::kfs::directory::encode_deleted_entry("), "directory mutation should use the deleted directory record encoder")
        assertFalse(storageSource.contains("unsafe fn encode_directory_entry_at("), "storage.rs should not own live directory entry writes")
        assertFalse(storageSource.contains("unsafe fn encode_deleted_directory_entry_at("), "storage.rs should not own deleted directory entry writes")
        assertTrue(namespaceMutationSource.contains("crate::kfs::directory_mutation::encode_directory_entry_at("), "namespace mutation should use the directory mutation owner for live entries")
        assertTrue(namespaceMutationSource.contains("crate::kfs::directory_mutation::encode_deleted_directory_entry_at("), "namespace mutation should use the directory mutation owner for deleted entries")
    }

    @Test
    fun kraftOsFilesystemDirectoryListingReadIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val rootSource = kernelKfsDir.resolve("root.rs").readText()
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val listingPath = kernelKfsDir.resolve("directory_listing.rs")
        assertTrue(listingPath.toFile().isFile, "KFS directory listing reads should have an explicit module")
        val listingSource = listingPath.readText()
        assertTrue(modSource.contains("pub mod directory_listing;"), "KFS directory listing module should be exported")
        assertTrue(listingSource.contains("pub unsafe fn copy_selected_directory_listing_into_cached"), "directory_listing.rs should own cached listing reads")
        assertTrue(listingSource.contains("pub unsafe fn copy_selected_directory_listing_into"), "directory_listing.rs should own selected directory listing reads")
        assertTrue(listingSource.contains("pub unsafe fn ensure_selected_directory_is_empty("), "directory_listing.rs should own directory emptiness scans")
        assertTrue(listingSource.contains("unsafe fn read_inode_path_metadata_cached("), "directory_listing.rs should own cached inode metadata reads")
        assertTrue(listingSource.contains("unsafe fn push_directory_entry"), "directory_listing.rs should own listing record formatting")
        assertTrue(listingSource.contains("unsafe fn push_u32_le"), "directory_listing.rs should own listing integer encoding")
        assertFalse(storageSource.contains("pub unsafe fn copy_selected_directory_listing_into_cached"), "storage.rs should not own cached listing reads")
        assertFalse(storageSource.contains("pub unsafe fn copy_selected_directory_listing_into"), "storage.rs should not own selected directory listing reads")
        assertFalse(storageSource.contains("unsafe fn push_directory_entry"), "storage.rs should not own listing record formatting")
        assertFalse(storageSource.contains("unsafe fn read_inode_path_metadata_cached("), "storage.rs should not own cached inode metadata reads")
        assertFalse(storageSource.contains("unsafe fn push_u32_le"), "storage.rs should not own listing integer encoding")
        assertFalse(storageSource.contains("unsafe fn ensure_selected_directory_is_empty("), "storage.rs should not own directory emptiness scans")
        assertTrue(namespaceMutationSource.contains("crate::kfs::directory_listing::ensure_selected_directory_is_empty("), "namespace mutation should use the directory listing owner for empty-directory scans")
        assertTrue(rootSource.contains("crate::kfs::directory_listing::copy_selected_directory_listing_into_cached("), "root.rs should use the directory listing owner")
    }

    @Test
    fun kraftOsFilesystemFileDataReadIsNotOwnedByStorageModule() {
        val kernelDir = Path.of("../../../guest/kraftos/kernel/src")
        val kernelKfsDir = kernelDir.resolve("kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val processSource = kernelDir.resolve("process.rs").readText()
        val fsSource = kernelDir.resolve("fs.rs").readText()
        val bootChainSource = kernelDir.resolve("boot_chain.rs").readText()

        val fileIoPath = kernelKfsDir.resolve("file_io.rs")
        assertTrue(fileIoPath.toFile().isFile, "KFS file data reads should have an explicit module")
        val fileIoSource = fileIoPath.readText()
        assertTrue(modSource.contains("pub mod file_io;"), "KFS file I/O module should be exported")
        assertTrue(fileIoSource.contains("pub unsafe fn copy_selected_file_range_to_ram"), "file_io.rs should own selected file reads")
        assertTrue(fileIoSource.contains("pub unsafe fn copy_selected_file_range_to_ram_profiled"), "file_io.rs should own profiled selected file reads")
        assertTrue(fileIoSource.contains("pub unsafe fn copy_file_range_to_ram"), "file_io.rs should own metadata-based file reads")
        assertTrue(fileIoSource.contains("pub unsafe fn copy_file_range_to_ram_profiled"), "file_io.rs should own profiled metadata-based file reads")
        assertTrue(fileIoSource.contains("volume: &mut crate::kfs::volume::KfsVolume"), "file reads should be scoped to an explicit volume")
        assertTrue(fileIoSource.contains("unsafe fn copy_extent_range_to_ram("), "file_io.rs should own extent copy planning")
        assertTrue(fileIoSource.contains("fn record_profiled_file_data_read("), "file_io.rs should own read profile accounting")
        assertTrue(fileIoSource.contains("fn record_profiled_file_path_data_read("), "file_io.rs should own path-specific read profile accounting")
        assertFalse(storageSource.contains("pub unsafe fn copy_selected_file_range_to_ram"), "storage.rs should not own selected file reads")
        assertFalse(storageSource.contains("pub unsafe fn copy_selected_file_range_to_ram_profiled"), "storage.rs should not own profiled selected file reads")
        assertFalse(storageSource.contains("pub unsafe fn copy_file_range_to_ram"), "storage.rs should not own metadata-based file reads")
        assertFalse(storageSource.contains("pub unsafe fn copy_file_range_to_ram_profiled"), "storage.rs should not own profiled metadata-based file reads")
        assertFalse(storageSource.contains("unsafe fn copy_extent_range_to_ram("), "storage.rs should not own extent copy planning")
        assertFalse(storageSource.contains("fn record_profiled_file_data_read("), "storage.rs should not own read profile accounting")
        assertFalse(storageSource.contains("fn record_profiled_file_path_data_read("), "storage.rs should not own path-specific read profile accounting")
        assertFalse(processSource.contains("crate::kfs::storage::copy_selected_file_range_to_ram"), "process.rs should not use storage-owned selected file reads")
        assertFalse(processSource.contains("crate::kfs::storage::copy_file_range_to_ram"), "process.rs should not use storage-owned metadata file reads")
        assertTrue(processSource.contains("crate::vfs::with_volume(file.volume,"), "process.rs should select the volume recorded by its VFS file")
        assertTrue(processSource.contains("crate::kfs::file_io::copy_file_range_to_ram_profiled("), "process.rs should use the file I/O owner for profiled metadata file reads")
        assertTrue(fsSource.contains("crate::kfs::file_io::copy_file_range_to_ram("), "fs.rs should use the file I/O owner")
        assertTrue(fsSource.contains("with_volume(volume_id,"), "fs.rs should route file reads through the resolved VFS volume")
        assertTrue(bootChainSource.contains("crate::kfs::file_io::copy_selected_file_range_to_ram("), "boot_chain.rs should use the file I/O owner")
        assertTrue(bootChainSource.contains("crate::vfs::with_volume(crate::vfs::VolumeId::Root,"), "boot_chain.rs should explicitly bind firmware reads to the root volume")
    }

    @Test
    fun kraftOsFilesystemFileDataWriteIsNotOwnedByStorageModule() {
        val kernelDir = Path.of("../../../guest/kraftos/kernel/src")
        val kernelKfsDir = kernelDir.resolve("kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val fsSource = kernelDir.resolve("fs.rs").readText()

        val fileWritePath = kernelKfsDir.resolve("file_write.rs")
        assertTrue(fileWritePath.toFile().isFile, "KFS file data writes should have an explicit module")
        val fileWriteSource = fileWritePath.readText()
        assertTrue(modSource.contains("pub mod file_write;"), "KFS file write module should be exported")
        assertTrue(fileWriteSource.contains("pub unsafe fn copy_ram_to_file_range("), "file_write.rs should own file range writes")
        assertTrue(fileWriteSource.contains("unsafe fn grow_file_capacity("), "file_write.rs should own file capacity growth")
        assertTrue(fileWriteSource.contains("fn min_u32("), "file_write.rs should own write chunk clamping")
        assertFalse(storageSource.contains("pub unsafe fn copy_ram_to_file_range("), "storage.rs should not own file range writes")
        assertFalse(storageSource.contains("unsafe fn grow_file_capacity("), "storage.rs should not own file capacity growth")
        assertFalse(storageSource.contains("fn min_u32("), "storage.rs should not own write chunk clamping")
        assertFalse(fsSource.contains("crate::kfs::storage::copy_ram_to_file_range("), "fs.rs should not use storage-owned file writes")
        assertTrue(fsSource.contains("crate::kfs::file_write::copy_ram_to_file_range("), "fs.rs should use the file write owner")
    }

    @Test
    fun kraftOsFilesystemNamespaceMutationIsNotOwnedByStorageModule() {
        val kernelDir = Path.of("../../../guest/kraftos/kernel/src")
        val kernelKfsDir = kernelDir.resolve("kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val fsSource = kernelDir.resolve("fs.rs").readText()
        val rootSource = kernelKfsDir.resolve("root.rs").readText()

        val namespaceMutationPath = kernelKfsDir.resolve("namespace_mutation.rs")
        assertTrue(namespaceMutationPath.toFile().isFile, "KFS namespace mutations should have an explicit module")
        val namespaceMutationSource = namespaceMutationPath.readText()
        assertTrue(modSource.contains("pub mod namespace_mutation;"), "KFS namespace mutation module should be exported")
        assertTrue(namespaceMutationSource.contains("pub unsafe fn open_file_for_write("), "namespace_mutation.rs should own mounted open-for-write namespace flow")
        assertTrue(namespaceMutationSource.contains("pub unsafe fn remove_file("), "namespace_mutation.rs should own mounted file removal")
        assertTrue(namespaceMutationSource.contains("pub unsafe fn rename_file"), "namespace_mutation.rs should own mounted file rename")
        assertTrue(namespaceMutationSource.contains("pub unsafe fn create_directory("), "namespace_mutation.rs should own mounted directory creation")
        assertTrue(namespaceMutationSource.contains("pub unsafe fn remove_directory("), "namespace_mutation.rs should own mounted directory removal")
        assertTrue(namespaceMutationSource.contains("volume: &mut crate::kfs::volume::KfsVolume"), "namespace mutation should be scoped to an explicit volume")
        assertTrue(namespaceMutationSource.contains("if volume.read_only"), "namespace mutation should fail closed for a read-only volume")
        assertTrue(namespaceMutationSource.contains("return Err(StorageError::READ_ONLY)"), "read-only namespace mutation should report ROFS")
        assertFalse(namespaceMutationSource.contains("_from_storage0("), "namespace mutation should not expose storage0-specific entrypoints")
        assertFalse(namespaceMutationSource.contains("mount::read_partition("), "namespace mutation should not mount partitions directly")
        assertFalse(namespaceMutationSource.contains("mount::read_superblock("), "namespace mutation should not read superblocks directly")
        assertTrue(namespaceMutationSource.contains("unsafe fn create_empty_file("), "namespace_mutation.rs should own empty file creation")
        assertTrue(namespaceMutationSource.contains("unsafe fn create_empty_directory("), "namespace_mutation.rs should own empty directory creation")
        assertTrue(namespaceMutationSource.contains("unsafe fn truncate_selected_file("), "namespace_mutation.rs should own selected file truncation")
        assertFalse(storageSource.contains("pub unsafe fn open_file_for_write_from_storage0("), "storage.rs should not own open-for-write namespace flow")
        assertFalse(storageSource.contains("pub unsafe fn remove_file_from_storage0("), "storage.rs should not own file removal")
        assertFalse(storageSource.contains("pub unsafe fn rename_file_from_storage0"), "storage.rs should not own file rename")
        assertFalse(storageSource.contains("pub unsafe fn create_directory_from_storage0("), "storage.rs should not own directory creation")
        assertFalse(storageSource.contains("pub unsafe fn remove_directory_from_storage0("), "storage.rs should not own directory removal")
        assertFalse(storageSource.contains("unsafe fn create_empty_file("), "storage.rs should not own empty file creation")
        assertFalse(storageSource.contains("unsafe fn create_empty_directory("), "storage.rs should not own empty directory creation")
        assertFalse(storageSource.contains("unsafe fn truncate_selected_file("), "storage.rs should not own selected file truncation")
        assertFalse(fsSource.contains("crate::kfs::storage::open_file_for_write_from_storage0("), "fs.rs should not use storage-owned open-for-write namespace flow")
        assertFalse(fsSource.contains("crate::kfs::storage::remove_file_from_storage0("), "fs.rs should not use storage-owned file removal")
        assertFalse(fsSource.contains("crate::kfs::storage::rename_file_from_storage0("), "fs.rs should not use storage-owned file rename")
        assertFalse(fsSource.contains("crate::kfs::storage::create_directory_from_storage0("), "fs.rs should not use storage-owned directory creation")
        assertFalse(fsSource.contains("crate::kfs::storage::remove_directory_from_storage0("), "fs.rs should not use storage-owned directory removal")
        assertFalse(fsSource.contains("crate::kfs::namespace_mutation::open_file_for_write_from_storage0("), "fs.rs should not bypass the mounted root context for open-for-write")
        assertFalse(fsSource.contains("crate::kfs::namespace_mutation::remove_file_from_storage0("), "fs.rs should not bypass the mounted root context for file removal")
        assertFalse(fsSource.contains("crate::kfs::namespace_mutation::rename_file_from_storage0("), "fs.rs should not bypass the mounted root context for file rename")
        assertFalse(fsSource.contains("crate::kfs::namespace_mutation::create_directory_from_storage0("), "fs.rs should not bypass the mounted root context for directory creation")
        assertFalse(fsSource.contains("crate::kfs::namespace_mutation::remove_directory_from_storage0("), "fs.rs should not bypass the mounted root context for directory removal")
        assertTrue(fsSource.contains("with_volume(volume_id,"), "fs.rs should route namespace operations through the resolved VFS volume")
        assertTrue(rootSource.contains("impl crate::kfs::volume::KfsVolume"), "mounted namespace operations should be methods on an explicit volume")
        assertTrue(rootSource.contains("crate::kfs::namespace_mutation::open_file_for_write(self,"), "root.rs should pass its volume to open-for-write")
        assertTrue(rootSource.contains("crate::kfs::namespace_mutation::remove_file(self,"), "root.rs should pass its volume to file removal")
        assertTrue(rootSource.contains("crate::kfs::namespace_mutation::rename_file("), "root.rs should use the namespace mutation owner for file rename")
        assertTrue(rootSource.contains("crate::kfs::namespace_mutation::create_directory(self,"), "root.rs should pass its volume to directory creation")
        assertTrue(rootSource.contains("crate::kfs::namespace_mutation::remove_directory(self,"), "root.rs should pass its volume to directory removal")
        assertTrue(rootSource.contains("if self.read_only"), "root volume methods should reject writes before mutation")
    }

    @Test
    fun kraftOsFilesystemAllocationMutationIsNotOwnedByStorageModule() {
        val kernelKfsDir = Path.of("../../../guest/kraftos/kernel/src/kfs")
        val modSource = kernelKfsDir.resolve("mod.rs").readText()
        val storageSource = optionalSource(kernelKfsDir.resolve("storage.rs"))
        val directoryMutationSource = kernelKfsDir.resolve("directory_mutation.rs").readText()
        val namespaceMutationSource = kernelKfsDir.resolve("namespace_mutation.rs")
            .takeIf { it.toFile().isFile }
            ?.readText()
            .orEmpty()

        val allocationPath = kernelKfsDir.resolve("allocation.rs")
        assertTrue(allocationPath.toFile().isFile, "KFS allocation mutation should have an explicit module")
        val allocationSource = allocationPath.readText()
        assertTrue(modSource.contains("pub mod allocation;"), "KFS allocation module should be exported")
        assertTrue(allocationSource.contains("pub unsafe fn allocate_inode("), "allocation.rs should own inode allocation")
        assertTrue(allocationSource.contains("pub unsafe fn allocate_contiguous_blocks("), "allocation.rs should own block run allocation")
        assertTrue(allocationSource.contains("pub unsafe fn mark_block_allocated("), "allocation.rs should own bitmap allocation mutation")
        assertTrue(allocationSource.contains("pub unsafe fn mark_block_free("), "allocation.rs should own bitmap free mutation")
        assertTrue(allocationSource.contains("fn selected_bitmap_layout("), "allocation.rs should own bitmap layout selection")
        assertFalse(storageSource.contains("unsafe fn allocate_inode("), "storage.rs should not own inode allocation")
        assertFalse(storageSource.contains("unsafe fn allocate_contiguous_blocks("), "storage.rs should not own block run allocation")
        assertFalse(storageSource.contains("unsafe fn mark_block_allocated("), "storage.rs should not own bitmap allocation mutation")
        assertFalse(storageSource.contains("unsafe fn mark_block_free("), "storage.rs should not own bitmap free mutation")
        assertFalse(storageSource.contains("fn selected_bitmap_layout("), "storage.rs should not own bitmap layout selection")
        assertTrue(namespaceMutationSource.contains("crate::kfs::allocation::allocate_inode("), "namespace mutation should use the allocation owner")
        assertTrue(directoryMutationSource.contains("crate::kfs::allocation::allocate_contiguous_blocks("), "directory mutation should use the allocation owner")
    }

    @Test
    fun k16KernelLegacyShellPathIsRemovedFromCurrentSource() {
        val kernelSourceDir = Path.of("../../../guest/kraftos/kernel/src")
        val mainSource = kernelSourceDir.resolve("main.rs").readText()
        val kernelSyscallSource = kernelSourceDir.resolve("syscall.rs").readText()
        val initSource = Path.of("../../../guest/kraftos/userland/init/init.c").readText()
        val processHeader = Path.of("../../../guest/kraftos/libc/include/kraft/process.h").readText()
        val syscallHeader = Path.of("../../../guest/kraftos/libc/include/kraft/syscalls.h").readText()
        val cSyscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()
        val sharedKraftSource = Path.of("../../../guest/kraftos/lib/libkraft/libkraft.c").readText()
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertFalse(Files.exists(kernelSourceDir.resolve("shell.rs")), "kernel shell dispatcher should be removed")
        assertFalse(Files.exists(kernelSourceDir.resolve("line.rs")), "kernel line discipline should be removed")
        assertFalse(Files.exists(kernelSourceDir.resolve("keyboard.rs")), "kernel keyboard line path should be removed")
        assertFalse(mainSource.contains("mod shell;"), "main.rs should not register the legacy shell module")
        assertFalse(mainSource.contains("shell::init();"), "kernel startup should not initialize the legacy shell module")
        assertTrue(Files.exists(Path.of("../../../guest/kraftos/userland/init/init.c")), "init should be a real C launcher source")
        assertFalse(initSource.contains("process::run("), "init should not hide shell lifecycle behind synchronous run")
        assertTrue(initSource.contains("#include <kraft/process.h>"), "init should use libc-lite process wrappers")
        assertTrue(initSource.contains("#define SHELL_PATH \"/bin/shell.kx\""), "init should launch the bundled shell path")
        assertTrue(initSource.contains("kraft_spawn_with_args(SHELL_PATH, 1, shell_args)"), "init should spawn the userland shell")
        assertTrue(initSource.contains("kraft_wait(pid, &status)"), "init should wait for the userland shell")
        assertTrue(initSource.contains("if (status == 0)"), "init should distinguish clean shell exits from faults")
        assertTrue(initSource.contains("_exit(status)"), "init should propagate shell faults")
        assertTrue(processHeader.contains("int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);"))
        assertTrue(processHeader.contains("int kraft_wait(int pid, int *status);"))
        assertTrue(syscallHeader.contains("extern int __kraft_sys_spawn(const void *request, unsigned int len)"))
        assertTrue(syscallHeader.contains("__asm__(\"kraft_sys_spawn\")"))
        assertTrue(syscallHeader.contains("extern int __kraft_sys_wait(unsigned int pid, int *status)"))
        assertTrue(syscallHeader.contains("__asm__(\"kraft_sys_wait\")"))
        assertTrue(syscallHeader.contains("extern int __kraft_sys_run(const void *request, unsigned int len)"))
        assertTrue(syscallHeader.contains("__asm__(\"kraft_sys_run\")"))
        assertTrue(cSyscallSource.contains("kraft_process_with_args(KRAFT_SPAWN_ARGV_REQUEST_MAGIC"))
        assertTrue(cSyscallSource.contains("__kraft_sys_spawn"))
        assertTrue(cSyscallSource.contains("__kraft_sys_wait((unsigned int)pid, status)"))
        assertTrue(cSyscallSource.contains("kraft_process_with_args(KRAFT_RUN_ARGV_REQUEST_MAGIC"))
        assertTrue(cSyscallSource.contains("__kraft_sys_run"))
        assertTrue(sharedKraftSource.contains("int kraft_sys_spawn(const void *request, unsigned int len)"))
        assertTrue(sharedKraftSource.contains("int kraft_sys_wait(unsigned int pid, int *status)"))
        assertTrue(sharedKraftSource.contains("int kraft_sys_run(const void *request, unsigned int len)"))
        assertTrue(shellSource.contains("kraft_run_with_args(program_path, argc, argv)"), "shell should launch utilities through argv requests")
        assertFalse(kernelSyscallSource.contains("abi_syscall::RUN_FORMAT_PATH"), "kernel should reject legacy path RUN format")
        assertFalse(initSource.contains("fn dispatch_command("), "interactive shell dispatch should not live in init")
        assertTrue(shellSource.contains("static void dispatch_command("), "userland shell should own command dispatch")
    }

    @Test
    fun k16UserlandShellDefinesPromptAndBuiltins() {
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertTrue(shellSource.contains("#define PROMPT \"K16> \""))
        assertTrue(shellSource.contains("static void dispatch_command("), "shell should name the dispatch boundary")
        assertTrue(shellSource.contains("char input[KRAFT_SHELL_INPUT_CAPACITY]"), "shell input should be explicit and bounded")
        assertTrue(shellSource.contains("static int matches_command("), "shell should share command matching")
        assertTrue(shellSource.contains("static int is_echo_command("), "shell should name echo command matching")
        assertTrue(shellSource.contains("run_echo("), "shell should handle the echo command")
        assertTrue(shellSource.contains("static void run_pwd("), "shell should name the pwd command")
        assertTrue(shellSource.contains("static unsigned int run_cd("), "shell should name the cd command")
        assertTrue(shellSource.contains("static unsigned int run_ticks("), "shell should name the ticks command")
        assertTrue(shellSource.contains("static unsigned int run_exec("), "shell should use generic exec dispatch")
        assertFalse(shellSource.contains("fn run_uname("), "uname should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_ls("), "ls should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_cat("), "cat should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_alloc_test("), "alloc should not need a hardcoded dispatch branch")
        assertTrue(shellSource.contains("COMMAND_PWD"), "shell should classify the pwd command")
        assertTrue(shellSource.contains("COMMAND_CD"), "shell should classify cd with an optional path argument")
        assertTrue(shellSource.contains("COMMAND_EXIT"), "shell should classify exit with an optional status code")
        assertTrue(shellSource.contains("COMMAND_EXEC"), "shell should classify non-builtins as generic exec")
        assertFalse(shellSource.contains("COMMAND_HELP"), "help should not be a shell builtin")
        assertFalse(shellSource.contains("COMMAND_UNAME"), "shell should not carry a uname command variant")
        assertFalse(shellSource.contains("COMMAND_LS"), "shell should not carry an ls command variant")
        assertFalse(shellSource.contains("COMMAND_CAT"), "shell should not carry a cat command variant")
        assertFalse(shellSource.contains("COMMAND_ALLOC_TEST"), "shell should not carry an alloc command variant")
        assertFalse(shellSource.contains("HELP\\n"), "shell should not embed a built-in help command list")
        assertTrue(shellSource.contains("stat(path_buffer, &metadata)"), "cd should validate paths through stat metadata")
        assertTrue(shellSource.contains("static int resolve_executable_path("), "executable path resolution should be named")
        assertTrue(shellSource.contains("resolve_executable_path(state->cwd, name, program_path)"), "shell should resolve executable paths through cwd")
        assertTrue(shellSource.contains("should_resolve_path_arg(name, raw_args, index)"), "filesystem utilities should keep cwd-aware path args")
        assertFalse(shellSource.contains("ALLOC_ALIAS"), "alloc should not be a shell alias")
        assertFalse(shellSource.contains("ALLOC_PROGRAM"), "alloc should not target removed alloc-test.kx")
        assertTrue(shellSource.contains("write_run_error((unsigned int)status)"), "missing programs should report run errors")
    }

    @Test
    fun k16CatUtilityReadsMotdThroughCLibc() {
        val catSource = Path.of("../../../guest/kraftos/userland/coreutils/cat.c").readText()
        val startupSource = Path.of("../../../guest/kraftos/libc/crt0.c").readText()
        val syscallHeader = Path.of("../../../guest/kraftos/libc/include/kraft/syscalls.h").readText()

        assertTrue(catSource.contains("#include <fcntl.h>"))
        assertTrue(catSource.contains("#include <string.h>"))
        assertTrue(catSource.contains("#include <unistd.h>"))
        assertTrue(catSource.contains("for (int index = 1; index < argc; index += 1)"), "cat should visit every argv path")
        assertTrue(catSource.contains("open(path, O_RDONLY)"))
        assertTrue(catSource.contains("read(fd, buffer, sizeof(buffer))"))
        assertTrue(catSource.contains("write_all(STDOUT_FILENO"))
        assertTrue(catSource.contains("close(fd)"))
        assertTrue(startupSource.contains("#define KRAFT_CRT_USER_MAIN kraft_main"))
        assertTrue(startupSource.contains("return KRAFT_CRT_USER_MAIN((int)argc, argv);"))
        assertTrue(syscallHeader.contains("int kraft_open(const char *path, unsigned int flags);"))
    }

    @Test
    fun k16UnameUtilityPrintsMachineNameThroughCLibc() {
        val unameSource = Path.of("../../../guest/kraftos/userland/coreutils/uname.c").readText()

        assertTrue(unameSource.contains("#include <unistd.h>"))
        assertTrue(unameSource.contains("write_all(STDOUT_FILENO"))
        assertTrue(unameSource.contains("\"K16\\n\""))
        assertTrue(unameSource.contains("return 1"))
        assertFalse(unameSource.contains("stdio.h"), "C uname must not depend on stdio")
    }

    @Test
    fun k16LsUtilityListsEveryArgvPathThroughCLibcFs() {
        val lsSource = Path.of("../../../guest/kraftos/userland/coreutils/ls.c").readText()
        val fsHeader = Path.of("../../../guest/kraftos/libc/include/kraft/fs.h").readText()
        val syscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()

        assertTrue(lsSource.contains("#include <kraft/fs.h>"))
        assertTrue(lsSource.contains("#include <string.h>"))
        assertTrue(lsSource.contains("#include <unistd.h>"))
        assertTrue(lsSource.contains("const char *path = argc > 1 ? argv[index] : \"/bin\""), "ls should keep the existing /bin default")
        assertTrue(lsSource.contains("read_dir(path, buffer, sizeof(buffer))"))
        assertFalse(lsSource.contains("stat("), "ls should use READ_DIR metadata instead of statting every child")
        assertTrue(lsSource.contains("read_u32_le(buffer + cursor + KRAFT_READ_DIR_ENTRY_NAME_LEN_OFFSET)"))
        assertTrue(lsSource.contains("file_type == KRAFT_FILE_TYPE_DIRECTORY"))
        assertTrue(lsSource.contains("status_name(status, \"READDIR\")"))
        assertFalse(lsSource.contains("stdio.h"), "C ls must not depend on stdio")
        assertTrue(fsHeader.contains("#define KRAFT_READ_DIR_ENTRY_FILE_TYPE_OFFSET 0"))
        assertTrue(fsHeader.contains("#define KRAFT_READ_DIR_ENTRY_NAME_LEN_OFFSET 4"))
        assertTrue(fsHeader.contains("#define KRAFT_READ_DIR_ENTRY_NAME_OFFSET 8"))
        assertTrue(fsHeader.contains("#define KRAFT_READ_DIR_ENTRY_SIZE_BYTES 4"))
        assertTrue(fsHeader.contains("#define KRAFT_READ_DIR_ENTRY_FIXED_BYTES 12"))
        assertTrue(fsHeader.contains("struct kraft_stat"))
        assertTrue(fsHeader.contains("int kraft_read_dir(const char *path, char *out, unsigned int out_len);"))
        assertTrue(fsHeader.contains("int kraft_stat(const char *path, struct kraft_stat *metadata);"))
        assertTrue(fsHeader.contains("#define read_dir(path, out, out_len) kraft_read_dir((path), (out), (out_len))"))
        assertTrue(fsHeader.contains("#define stat(path, metadata) kraft_stat((path), (metadata))"))
        assertTrue(syscallSource.contains("int kraft_read_dir(const char *path, char *out, unsigned int out_len)"))
    }

    @Test
    fun k16StatUtilityReadsMetadataThroughCLibcFs() {
        val statSource = Path.of("../../../guest/kraftos/userland/coreutils/stat.c").readText()
        val fsHeader = Path.of("../../../guest/kraftos/libc/include/kraft/fs.h").readText()
        val syscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()

        assertTrue(statSource.contains("#include <kraft/fs.h>"))
        assertTrue(statSource.contains("#include <unistd.h>"))
        assertTrue(statSource.contains("stat(path, &metadata)"))
        assertTrue(statSource.contains("metadata.file_type == KRAFT_FILE_TYPE_REGULAR"))
        assertTrue(statSource.contains("metadata.file_type == KRAFT_FILE_TYPE_DIRECTORY"))
        assertTrue(statSource.contains("write_decimal(STDOUT_FILENO, metadata.size_bytes)"))
        assertTrue(statSource.contains("char output[10]"), "stat decimal output should use a caller-owned digit buffer")
        assertTrue(statSource.contains("output_len += 1"), "stat decimal output should batch digits before writing")
        assertFalse(statSource.contains("write_all(fd, &ch, 1)"), "stat decimal output should not flush one syscall per digit")
        assertTrue(statSource.contains("status_name(status, \"STAT\")"))
        assertFalse(statSource.contains("stdio.h"), "C stat must not depend on stdio")
        assertTrue(fsHeader.contains("#define KRAFT_STAT_METADATA_BYTES 16"))
        assertTrue(syscallSource.contains("int kraft_stat(const char *path, struct kraft_stat *metadata)"))
    }

    @Test
    fun k16CpUtilityCopiesRegularFilesThroughCLibc() {
        val cpSource = Path.of("../../../guest/kraftos/userland/coreutils/cp.c").readText()

        assertTrue(cpSource.contains("#include <fcntl.h>"))
        assertTrue(cpSource.contains("#include <string.h>"))
        assertTrue(cpSource.contains("#include <unistd.h>"))
        assertTrue(cpSource.contains("argc != 3"), "cp should keep the existing two-argument contract")
        assertTrue(cpSource.contains("open(source_path, O_RDONLY)"))
        assertTrue(cpSource.contains("open(destination_path, O_WRONLY | O_CREAT | O_TRUNC)"))
        assertTrue(cpSource.contains("read(source, buffer, sizeof(buffer))"))
        assertTrue(cpSource.contains("write_all(destination"))
        assertTrue(cpSource.contains("write_text(STDOUT_FILENO, \"COPIED \")"))
        assertTrue(cpSource.contains("status_name(status, \"IO\")"))
        assertFalse(cpSource.contains("stdio.h"), "C cp must not depend on stdio")
    }

    @Test
    fun k16MvUtilityRenamesRegularFilesThroughCLibcFs() {
        val mvSource = Path.of("../../../guest/kraftos/userland/coreutils/mv.c").readText()
        val fsHeader = Path.of("../../../guest/kraftos/libc/include/kraft/fs.h").readText()
        val syscallHeader = Path.of("../../../guest/kraftos/libc/include/kraft/syscalls.h").readText()
        val syscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()

        assertTrue(mvSource.contains("#include <kraft/fs.h>"))
        assertTrue(mvSource.contains("#include <unistd.h>"))
        assertTrue(mvSource.contains("argc != 3"), "mv should keep the existing two-argument contract")
        assertTrue(mvSource.contains("stat(destination_path, &metadata)"))
        assertTrue(mvSource.contains("rename(source_path, destination_path)"))
        assertTrue(mvSource.contains("write_text(STDOUT_FILENO, \"MOVED \")"))
        assertTrue(mvSource.contains("status_name(status, \"RENAME\")"))
        assertFalse(mvSource.contains("stat(source_path"), "mv should trust the rename syscall for source validation")
        assertFalse(mvSource.contains("metadata.file_type"), "mv should not duplicate source file type checks in userland")
        assertFalse(mvSource.contains("stdio.h"), "C mv must not depend on stdio")
        assertTrue(fsHeader.contains("#define KRAFT_RENAME_REQUEST_MAGIC 0x4d414e52u"))
        assertTrue(fsHeader.contains("#define KRAFT_MAX_RENAME_REQUEST_BYTES 468"))
        assertTrue(fsHeader.contains("int kraft_rename(const char *old_path, const char *new_path);"))
        assertTrue(fsHeader.contains("#define rename(old_path, new_path) kraft_rename((old_path), (new_path))"))
        assertTrue(syscallHeader.contains("extern int __kraft_sys_rename"))
        assertTrue(syscallHeader.contains("__asm__(\"kraft_sys_rename\")"))
        assertTrue(syscallSource.contains("int kraft_rename(const char *old_path, const char *new_path)"))
        assertTrue(syscallSource.contains("put_u32_le(request + 0, KRAFT_RENAME_REQUEST_MAGIC)"))
        assertTrue(syscallSource.contains("__kraft_sys_rename(request, request_len)"))
    }

    @Test
    fun k16WriteUtilityCreatesRegularFileThroughCLibc() {
        val writeSource = Path.of("../../../guest/kraftos/userland/coreutils/write.c").readText()
        val unistdHeader = Path.of("../../../guest/kraftos/libc/include/unistd.h").readText()
        val fcntlHeader = Path.of("../../../guest/kraftos/libc/include/fcntl.h").readText()
        val stringHeader = Path.of("../../../guest/kraftos/libc/include/string.h").readText()

        assertTrue(writeSource.contains("#include <fcntl.h>"))
        assertTrue(writeSource.contains("#include <string.h>"))
        assertTrue(writeSource.contains("#include <unistd.h>"))
        assertTrue(writeSource.contains("argc == 4 && strcmp(argv[1], \"--append\") == 0"))
        assertTrue(writeSource.contains("O_WRONLY | O_CREAT | O_TRUNC"))
        assertTrue(writeSource.contains("O_WRONLY | O_CREAT | O_APPEND"))
        assertTrue(writeSource.contains("unsigned int len = strlen(payload)"))
        assertTrue(writeSource.contains("write_all(fd, payload, len)"))
        assertTrue(writeSource.contains("write_text(STDOUT_FILENO, \"WROTE \")"))
        assertTrue(writeSource.contains("char output[10]"), "write decimal output should use a caller-owned digit buffer")
        assertTrue(writeSource.contains("output_len += 1"), "write decimal output should batch digits before writing")
        assertFalse(writeSource.contains("write_all(fd, &ch, 1)"), "write decimal output should not flush one syscall per digit")
        assertTrue(unistdHeader.contains("int open(const char *path, int flags);"))
        assertTrue(unistdHeader.contains("int write(int fd, const void *buffer, unsigned int count)"))
        assertTrue(unistdHeader.contains("__asm__(\"kraft_sys_write\")"))
        assertTrue(unistdHeader.contains("int close(int fd) __asm__(\"kraft_sys_close\");"))
        assertTrue(fcntlHeader.contains("#define O_CREAT KRAFT_OPEN_CREATE"))
        assertTrue(fcntlHeader.contains("#define O_APPEND KRAFT_OPEN_APPEND"))
        assertTrue(stringHeader.contains("unsigned int strlen(const char *text)"))
        assertTrue(stringHeader.contains("int strcmp(const char *left, const char *right)"))
    }

    @Test
    fun k16MutatingUtilitiesRenderReadOnlyFilesystemErrors() {
        val fsHeader = Path.of("../../../guest/kraftos/libc/include/kraft/fs.h").readText()
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()
        val mutatingSources = listOf("cp", "mkdir", "mv", "rm", "rmdir").associateWith { utility ->
            Path.of("../../../guest/kraftos/userland/coreutils/$utility.c").readText()
        }
        val writeSource = Path.of("../../../guest/kraftos/userland/coreutils/write.c").readText()

        assertTrue(fsHeader.contains("#define KRAFT_ERROR_READ_ONLY 0xffffffe2u"))
        assertTrue(shellSource.contains("return \"ROFS\";"))
        mutatingSources.forEach { (utility, source) ->
            assertTrue(source.contains("return \"ROFS\";"), "$utility should render ERROR_READ_ONLY as ROFS")
        }
        assertTrue(writeSource.contains("static int write_all"))
        assertTrue(writeSource.contains("return result < 0 ? result : (int)0xfffffff2u;"))
        assertTrue(writeSource.contains("status_name(status, \"IO\")"))
        assertTrue(writeSource.contains("write_path_error(write_status, path)"))
        assertFalse(writeSource.contains("write: open failed:"))
    }

    @Test
    fun k16RmUtilityRemovesRegularFileThroughCLibc() {
        val rmSource = Path.of("../../../guest/kraftos/userland/coreutils/rm.c").readText()
        val unistdHeader = Path.of("../../../guest/kraftos/libc/include/unistd.h").readText()
        val syscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()

        assertTrue(rmSource.contains("#include <unistd.h>"))
        assertTrue(rmSource.contains("unlink(path)"))
        assertTrue(rmSource.contains("write_text(STDOUT_FILENO, \"REMOVED \")"))
        assertTrue(rmSource.contains("status_name(status, \"UNLINK\")"))
        assertFalse(rmSource.contains("stdio.h"), "C rm must not depend on stdio")
        assertTrue(unistdHeader.contains("int kraft_unlink(const char *path);"))
        assertTrue(unistdHeader.contains("#define unlink(path) kraft_unlink(path)"))
        assertTrue(syscallSource.contains("int kraft_unlink(const char *path)"))
    }

    @Test
    fun k16MkdirAndRmdirUtilitiesMutateDirectoriesThroughCLibc() {
        val mkdirSource = Path.of("../../../guest/kraftos/userland/coreutils/mkdir.c").readText()
        val rmdirSource = Path.of("../../../guest/kraftos/userland/coreutils/rmdir.c").readText()
        val unistdHeader = Path.of("../../../guest/kraftos/libc/include/unistd.h").readText()
        val syscallSource = Path.of("../../../guest/kraftos/libc/syscalls.c").readText()

        assertTrue(mkdirSource.contains("#include <unistd.h>"))
        assertTrue(mkdirSource.contains("mkdir(path)"))
        assertTrue(mkdirSource.contains("write_text(STDOUT_FILENO, \"CREATED \")"))
        assertTrue(mkdirSource.contains("status_name(status, \"MKDIR\")"))
        assertFalse(mkdirSource.contains("stdio.h"), "C mkdir must not depend on stdio")
        assertTrue(rmdirSource.contains("#include <unistd.h>"))
        assertTrue(rmdirSource.contains("rmdir(path)"))
        assertTrue(rmdirSource.contains("write_text(STDOUT_FILENO, \"REMOVED \")"))
        assertTrue(rmdirSource.contains("status_name(status, \"RMDIR\")"))
        assertFalse(rmdirSource.contains("stdio.h"), "C rmdir must not depend on stdio")
        assertTrue(unistdHeader.contains("int kraft_mkdir(const char *path);"))
        assertTrue(unistdHeader.contains("int kraft_rmdir(const char *path);"))
        assertTrue(unistdHeader.contains("#define mkdir(path) kraft_mkdir(path)"))
        assertTrue(unistdHeader.contains("#define rmdir(path) kraft_rmdir(path)"))
        assertTrue(syscallSource.contains("int kraft_mkdir(const char *path)"))
        assertTrue(syscallSource.contains("int kraft_rmdir(const char *path)"))
    }

    @Test
    fun k16UserlandTicksUsesCShellAndKernelTimerApi() {
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()
        val timerSource = Path.of("../../../guest/kraftos/kernel/src/timer.rs").readText()

        assertTrue(shellSource.contains("static unsigned char ticks_bytes[8];"), "shell ticks should use a stable caller-owned syscall buffer")
        assertTrue(shellSource.contains("__k16_syscall1("), "shell ticks should use the raw GAME_TICKS syscall helper")
        assertTrue(shellSource.contains("KRAFT_SYSCALL_GAME_TICKS"), "shell should name the timer0 syscall number")
        assertTrue(shellSource.contains("\"TICKS \""), "ticks should print a stable decimal prefix")
        assertTrue(shellSource.contains("write_decimal_words("), "shell should format scalar full-width timer words")
        assertTrue(
            shellSource.contains("double_decimal_digits_and_add_bit"),
            "shell should format decimal without relying on 64-bit division",
        )
        assertTrue(timerSource.contains("pub type U64Parts = k16_rt::U64Parts"), "timer module should expose explicit low/high timer parts")
        assertTrue(timerSource.contains("pub struct TickInstant"), "timer module should expose a named tick instant")
        assertTrue(timerSource.contains("pub struct TickDuration"), "timer module should expose a named tick duration")
        assertTrue(timerSource.contains("pub fn now_ticks() -> TickInstant"), "timer module should expose current game ticks as an instant")
        assertTrue(
            timerSource.contains("pub fn monotonic_nanos() -> U64Parts"),
            "timer module should keep monotonic nanos as diagnostic parts",
        )
        assertFalse(timerSource.contains("k16_rt::trap_value()"), "timer value should come from MMIO instead of interrupt payload")
    }

    @Test
    fun k16UserlandShellDefinesReadableLineEditingSemantics() {
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertTrue(
            shellSource.contains("byte >= 0x20 && byte <= 0x7e"),
            "printable input bytes should flow through a named printable branch",
        )
        assertTrue(
            shellSource.contains("byte == '\\b' || byte == 0x7f"),
            "backspace and delete should erase editable userland input",
        )
        assertTrue(
            shellSource.contains("byte == '\\n' || byte == '\\r'"),
            "newline and carriage return should complete the current userland line",
        )
        assertTrue(shellSource.contains("state.input_len = 0"), "shell should clear stale line bytes before each prompt")
    }

    @Test
    fun k16KernelEntrypointLaunchesInitProgram() {
        val mainSource = Path.of("../../../guest/kraftos/kernel/src/main.rs").readText()
        val initSource = Path.of("../../../guest/kraftos/kernel/src/init.rs").readText()
        val processSource = Path.of("../../../guest/kraftos/kernel/src/process.rs").readText()

        assertTrue(mainSource.contains("mod console;"), "kernel should register console for fd stdout")
        assertTrue(mainSource.contains("mod init;"), "kernel should register the init launcher module")
        assertTrue(mainSource.contains("console::init();"), "kernel should initialize console before userland")
        assertTrue(mainSource.contains("trap::initialize();"), "kernel should initialize traps before userland")
        assertTrue(mainSource.contains("control::set_ready();"), "kernel should mark the machine ready before entering init")
        assertTrue(mainSource.contains("init::launch()"), "kernel entrypoint should launch ROOT:/bin/init.kx")
        assertTrue(
            initSource.contains("const INIT_PROGRAM_PATH: &[u8] = b\"/bin/init.kx\";"),
            "init.rs should name the ROOT KFS init K16E path",
        )
        assertTrue(
            initSource.contains("process::begin_translated_init_from_storage0(INIT_PROGRAM_PATH, boot_info)"),
            "init.rs should launch init through the named ROOT KFS K16E path",
        )
        assertTrue(
            processSource.contains("crate::fs::open_root_file_cached_components(path.components())"),
            "process loading should open dynamic user K16E files through mounted ROOT KFS",
        )
        assertFalse(mainSource.contains("mod shell;"), "kernel entrypoint should not register the legacy shell")
        assertFalse(mainSource.contains("shell::init();"), "kernel entrypoint should not start the legacy shell")
        assertFalse(mainSource.contains("fn idle_once()"), "kernel entrypoint should not remain in the legacy idle loop")
    }

    @Test
    fun k16KernelChildExitCompletionUsesSharedHelper() {
        val mainSource = Path.of("../../../guest/kraftos/kernel/src/main.rs").readText()
        val syscallSource = Path.of("../../../guest/kraftos/kernel/src/syscall.rs").readText()
        val trapSource = Path.of("../../../guest/kraftos/kernel/src/trap.rs").readText()
        val childExitPath = Path.of("../../../guest/kraftos/kernel/src/child_exit.rs")
        assertTrue(Files.exists(childExitPath), "kernel should keep child-exit completion in a shared helper module")
        val childExitSource = childExitPath.readText()
        val processSource = Path.of("../../../guest/kraftos/kernel/src/process.rs").readText()

        assertTrue(mainSource.contains("mod child_exit;"), "kernel should register the shared child-exit helper")
        assertTrue(
            syscallSource.contains("child_exit::complete_child_exit(status)"),
            "explicit EXIT should delegate child completion to the shared helper",
        )
        assertTrue(
            trapSource.contains("child_exit::complete_child_exit(status)"),
            "translated user fault exits should delegate child completion to the shared helper",
        )
        assertTrue(
            childExitSource.contains("let mut resume = process::ParentResume::empty();"),
            "child completion should allocate ParentResume in caller-owned storage",
        )
        assertTrue(
            childExitSource.contains("process::finish_child_for_exit_into(status, &mut resume)"),
            "child completion should fill ParentResume through an output reference instead of returning it by value",
        )
        assertTrue(
            childExitSource.contains("process::destroy_exited_address_space(&resume)"),
            "child completion should pass ParentResume by reference for cleanup",
        )
        assertTrue(
            childExitSource.contains("process::resume_parent_context(&resume)"),
            "child completion should pass ParentResume by reference for parent resume",
        )
        assertFalse(
            syscallSource.contains("process::finish_child_for_exit_into("),
            "explicit EXIT should not duplicate child completion internals",
        )
        assertFalse(
            trapSource.contains("process::finish_child_for_exit_into("),
            "translated user fault exits should not duplicate child completion internals",
        )
        assertTrue(
            processSource.contains("pub const fn empty() -> Self"),
            "ParentResume should provide explicit zeroed caller-owned storage",
        )
        assertTrue(
            processSource.contains("pub unsafe fn finish_child_for_exit_into(") &&
                processSource.contains("out: &mut ParentResume,"),
            "process should expose a caller-owned ParentResume fill API",
        )
    }

    @Test
    fun k16KernelFontCoversWorkingShellText() {
        val fontSource = Path.of("../../../guest/kraftos/kernel/src/font.rs").readText()
        val shellSource = Path.of("../../../guest/kraftos/userland/shell/shell.c").readText()

        assertTrue(fontSource.contains("terminal_font::TERMINAL_FONT_ROWS"))
        assertTrue(fontSource.contains("terminal_font::FALLBACK_ROWS"))
        assertTrue(fontSource.contains("TERMINAL_FONT_ROWS[byte as usize]"))
        assertFalse(fontSource.contains("byte -"), "kernel font lookup should not use range-offset indexing")
        assertFalse(fontSource.contains("match byte"), "kernel font lookup should stay table-driven")
        assertFalse(shellSource.contains("display_byte("), "userland shell echo should not force uppercase display")
    }

    @Test
    fun k16KernelSleepTicksUsesTimer0MmioParts() {
        val timerSource = Path.of("../../../guest/kraftos/kernel/src/timer.rs").readText()
        val runtimeSource = Path.of("../../../guest/kraftos/runtime/src/time.rs").readText()
        val runtimeLibSource = Path.of("../../../guest/kraftos/runtime/src/lib.rs").readText()

        assertTrue(
            timerSource.contains("read_game_ticks_words(&mut low, &mut high)"),
            "kernel sleep_ticks should use scalar full-width timer0 reads",
        )
        assertTrue(
            timerSource.contains("TickInstant::now()"),
            "kernel sleep_ticks should start from a named tick instant",
        )
        assertTrue(
            timerSource.contains("TickDuration::from_ticks(ticks)"),
            "kernel sleep_ticks should express the delay as a tick duration",
        )
        assertTrue(
            timerSource.contains("k16_rt::wait_once();"),
            "kernel sleep_ticks should use the resumable wait primitive while blocked",
        )
        assertFalse(
            timerSource.contains("k16_rt::yield_once();"),
            "kernel sleep_ticks should not busy-yield directly",
        )
        assertTrue(
            runtimeSource.contains("read_split_u64_parts(TIMER0_GAME_TICKS_LOW, TIMER0_GAME_TICKS_HIGH)"),
            "runtime should read full-width game ticks from timer0 MMIO",
        )
        assertTrue(
            runtimeSource.contains("read_split_u64_parts(TIMER0_MONOTONIC_NANOS_LOW, TIMER0_MONOTONIC_NANOS_HIGH)"),
            "runtime should read full-width monotonic nanos from timer0 MMIO",
        )
        assertTrue(
            runtimeLibSource.contains("timer0_game_ticks_low") &&
                runtimeLibSource.contains("timer0_game_ticks_high") &&
                runtimeLibSource.contains("timer0_game_ticks_parts") &&
                runtimeLibSource.contains("timer0_monotonic_nanos_parts") &&
                runtimeLibSource.contains("U64Parts"),
            "runtime should export the reusable low/high timer API",
        )
        assertTrue(timerSource.contains("fn checked_add("), "kernel sleep_ticks should build a full-width deadline")
        assertTrue(timerSource.contains("fn has_reached("), "kernel sleep_ticks should compare full-width deadlines")
        assertFalse(timerSource.contains("fn add_ticks("), "kernel sleep_ticks should not expose raw add helpers")
    }

    @Test
    fun k16KernelPayloadInspectToolDoesNotEnforceFixedWindowBudget() {
        val toolPath = Path.of("../../../tools/k16-kernel-payload-inspect.sh")

        assertTrue(Files.exists(toolPath), "K16 kernel payload inspect tool should exist")

        val source = toolPath.readText()
        assertTrue(source.contains("KERNEL_LOAD_ADDR=0x00004000"))
        assertTrue(source.contains("payload_bytes="))
        assertTrue(source.contains("memory_size="))
        assertFalse(source.contains("KERNEL_LIMIT_BYTES"))
        assertFalse(source.contains("MIN_HEADROOM_BYTES"))
        assertFalse(source.contains("headroom_bytes"))
    }

    @Test
    fun k16KernelTimerSmokeBuildUsesFirmwareSizeProfile() {
        val toolPath = Path.of("../../../tools/k16-kernel-timer-smoke.sh")

        assertTrue(Files.exists(toolPath), "K16 kernel timer smoke tool should exist")

        val source = toolPath.readText()
        assertTrue(source.contains("-Copt-level=z"))
        assertTrue(source.contains("-C opt-level=z"))
    }

    @Test
    fun bundledK16KernelPayloadUsesFixedLoadBaseWithoutFixedWindowBudget() {
        val artifactPath = Path.of("build/generated/k16-firmware-artifacts/kernel.kx")
        val bytes = artifactPath.readBytes()

        assertContentEquals("K16E".encodeToByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes.u16Le(offset = 4), "K16E version")
        assertEquals(32, bytes.u16Le(offset = 6), "K16E header size")
        assertEquals(32, bytes.u32Le(offset = 16), "K16E section table offset")
        assertEquals(1, bytes.u32Le(offset = 20), "K16E section count")
        assertEquals(2, bytes.u32Le(offset = 24), "K16E ABI kind should be kernel")
        assertEquals(1, bytes.u32Le(offset = 32), "K16E section kind should be load")
        assertEquals(K16_KERNEL_LOAD_ADDR, bytes.u32Le(offset = 36), "K16E kernel load address")
        assertEquals(52, bytes.u32Le(offset = 40), "K16E kernel payload offset")

        val payloadBytes = bytes.u32Le(offset = 44)
        val memorySize = bytes.u32Le(offset = 48)

        assertTrue(payloadBytes > 0, "K16 kernel payload should not be empty")
        assertTrue(memorySize >= payloadBytes, "K16E kernel memory size should cover file payload")
        assertTrue(
            memorySize < K16_COMPUTER_MEMORY_BYTES - K16_KERNEL_LOAD_ADDR,
            "K16E kernel memory size should leave guest RAM outside the loaded image",
        )
    }

    @Test
    fun bundledK16BiosSplashIsObservableBeforeStorageBoot() {
        val workspace = createTempDirectory("k16-firmware-splash-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val splashControl = runRuntimeServerTick(runtime)

                assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
                assertBiosRetainedConsole(retainedState(runtime))

                var bootControl = splashControl
                var tick = 1
                while (tick < 24 && bootControl.status != NativeK16ComputerControl.STATUS_READY) {
                    bootControl = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
                    tick += 1
                }
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(NativeK16ComputerControl.STATUS_READY, bootControl.status)
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelRetainedConsoleVisible(runtime, bootControl, debug)
            }
    }

    @Test
    fun runtimeDeviceLateViewerReceivesKraftOsRetainedSnapshot() {
        val workspace = createTempDirectory("k16-runtime-device-splash-display-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val displayNetwork = FirmwareCapturingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 219,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
                serverThreadDispatcher = directServerThreadDispatcher,
                displayNetwork = displayNetwork,
            )

        try {
            device.turnOn()
            repeat(80) {
                device.serverTick()
                val snapshot = device.snapshotRuntimeState()
                if (snapshot != null && terminalText(snapshot).contains("K16> ")) {
                    val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000219")
                    assertTrue(device.attachRetainedDisplayViewer(playerUuid))
                    repeat(16) {
                        device.serverTick()
                        val payload = displayNetwork.sentPayloads().singleOrNull()
                        if (payload != null) {
                            val installed =
                                assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(payload))
                            assertKraftOsRetainedConsole(installed.state)
                            return
                        }
                    }
                    error("late retained viewer did not receive a KraftOS snapshot")
                }
            }
            val snapshot = device.snapshotRuntimeState()
            error("K16 runtime device did not reach shell prompt; terminal: ${snapshot?.let(::terminalText)}")
        } finally {
            device.close()
        }
    }

    @Test
    fun bundledK16BiosSplashRendersDistinctBannerGlyphs() {
        val workspace = createTempDirectory("k16-firmware-splash-glyph-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                runRuntimeServerTick(runtime)
                val state = retainedState(runtime)
                assertBiosRetainedConsole(state)
                val instances = assertIs<RetainedMaskInstanceBuffer>(state.resource(BIOS_INSTANCES_ID)?.content)
                val biosGlyphCodes = "16ABCDEIKLNOSTV "
                for ((index, byte) in "K16 BIOS".encodeToByteArray().withIndex()) {
                    val instance = instances.instances[index]
                    assertEquals(0, instance.sourceX)
                    assertEquals(biosGlyphCodes.indexOf(byte.toInt().toChar()) * 8, instance.sourceY)
                    assertEquals(8 + index * 8, instance.destinationX)
                }
            }
    }

    @Test
    fun bundledK16SystemStorage0RejectsRuntimeSnapshotResumeWithoutRetainedGpuState() {
        val workspace = createTempDirectory("k16-firmware-restore-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        val machineSnapshot =
            K16ComputerRuntimeFactory
                .createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                ).use { runtime ->
                    val control = runThroughBiosSplashAndBoot(runtime)
                    assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                    runtime.machineSnapshot()
                }
        val storage0BeforeRestore = storage0Path.readBytes()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    snapshot = machineSnapshot,
                )
            }
        assertTrue(failure.message.orEmpty().contains("cannot preserve retained gpu0 state"))
        assertContentEquals(storage0BeforeRestore, storage0Path.readBytes())
    }

    private fun retainedState(runtime: K16ComputerRuntime): RetainedDisplayState {
        val viewerToken = 1L
        assertTrue(runtime.attachRetainedDisplayViewer(viewerToken, computerId = 1) > 0)
        try {
            val payload = runtime.drainRetainedDisplayPayload(viewerToken)
            assertTrue(payload.isNotEmpty(), "late retained viewer should receive an atomic snapshot")
            return assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(payload)).state
        } finally {
            assertTrue(runtime.detachRetainedDisplayViewer(viewerToken))
        }
    }

    private fun assertBiosRetainedConsole(state: RetainedDisplayState) {
        assertIs<RetainedMask1Bpp>(state.resource(BIOS_FONT_MASK_ID)?.content)
        val instances = assertIs<RetainedMaskInstanceBuffer>(state.resource(BIOS_INSTANCES_ID)?.content)
        assertEquals(18, instances.capacity)
        assertTrue(state.drawList.commands.isNotEmpty())
    }

    private fun assertKraftOsRetainedConsole(state: RetainedDisplayState) {
        assertIs<RetainedMask1Bpp>(state.resource(KRAFTOS_FONT_MASK_ID)?.content)
        val instances = assertIs<RetainedMaskInstanceBuffer>(state.resource(KRAFTOS_INSTANCES_ID)?.content)
        assertEquals(K16_TERMINAL_COLUMNS * K16_TERMINAL_ROWS, instances.capacity)
        assertTrue(state.drawList.commands.isNotEmpty())
    }

    private fun assertKernelRetainedConsoleVisible(
        runtime: K16ComputerRuntime,
        control: NativeK16ComputerControl,
        debug: String,
    ) {
        assertEquals(NativeK16ComputerControl.STATUS_READY, control.status, "debug: $debug")
        assertKraftOsRetainedConsole(retainedState(runtime))
    }

    private fun runThroughBiosSplashAndBoot(runtime: K16ComputerRuntime): NativeK16ComputerControl {
        val splashControl = runRuntimeServerTick(runtime)
        assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
        var control = splashControl
        var tick = 1
        while (tick < 24 && control.status != NativeK16ComputerControl.STATUS_READY &&
            control.status != NativeK16ComputerControl.STATUS_HALTED
        ) {
            control = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
            tick += 1
        }
        return control
    }

    private fun runUntilTerminalText(
        runtime: K16ComputerRuntime,
        expected: String,
    ): NativeK16ComputerControl {
        var control = runThroughBiosSplashAndBoot(runtime)
        return continueUntilTerminalText(runtime, expected, control)
    }

    private fun continueUntilTerminalText(
        runtime: K16ComputerRuntime,
        expected: String,
        initialControl: NativeK16ComputerControl,
    ): NativeK16ComputerControl {
        var control = initialControl
        repeat(32) {
            val terminal = terminalText(runtime.machineSnapshot())
            if (terminal.contains(expected)) {
                return control
            }
            control = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
        }
        val snapshot = runtime.machineSnapshot()
        val terminal = terminalText(snapshot)
        val debug = runtime.outputSnapshot().decodeToString()
        error(
            "K16 firmware test did not observe '$expected'; status: ${control.status}; " +
                snapshotBootCpuDebug(snapshot) + "; " +
                "keyboard0Events: ${snapshotKeyboard0EventCount(snapshot)}; debug: $debug; terminal: $terminal",
        )
    }

    private fun runRuntimeServerTick(
        runtime: K16ComputerRuntime,
        maxTurns: Int = 8,
    ): NativeK16ComputerControl {
        runtime.advanceGameTicks(1)
        return runtime.tick(maxTurns = maxTurns)
    }

    private fun runShellCommand(
        runtime: K16ComputerRuntime,
        command: String,
    ) {
        for (byte in "$command\n".encodeToByteArray()) {
            runtime.pushKeyboardChar(byte)
        }
        runtime.advanceGameTicks(1)
        var control = runtime.tick(maxTurns = 256)
        var snapshot = runtime.machineSnapshot()
        var turns = 1
        while (turns < 32 && snapshotKeyboard0EventCount(snapshot) != 0) {
            control = runtime.tick(maxTurns = 256)
            snapshot = runtime.machineSnapshot()
            turns += 1
        }
        repeat(4) {
            control = runtime.tick(maxTurns = 256)
            snapshot = runtime.machineSnapshot()
        }
        val terminal = terminalText(snapshot)

        assertEquals(NativeK16ComputerControl.STATUS_READY, control.status, "command: $command")
        assertEquals(0, control.panicCode, "command: $command")
        assertEquals(
            0,
            snapshotKeyboard0EventCount(snapshot),
            "shell command should consume all keyboard input; command: $command; terminal: $terminal",
        )
    }

    private fun snapshotTimer0GameTicks(snapshot: ByteArray): Long {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_TIMER0_DEVICE_KIND) {
                assertEquals(K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE, payloadSize)
                return buffer.getLong(payloadOffset)
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a timer0 device record")
    }

    private fun snapshotBootCpuDebug(snapshot: ByteArray): String {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        require(cpuCount > 0)
        val cpuOffset = headerSize + ramSize.toInt()
        return "bootPc: ${buffer.getInt(cpuOffset + 0x10).toString(16)}; " +
            "trapPc: ${buffer.getInt(cpuOffset + 0x1c).toString(16)}; " +
            "trapCause: ${buffer.getInt(cpuOffset + 0x18).toString(16)}; " +
            "trapValue: ${buffer.getInt(cpuOffset + 0x20).toString(16)}; " +
            "trapArgs: ${buffer.getInt(cpuOffset + 0x80).toString(16)}," +
            "${buffer.getInt(cpuOffset + 0x84).toString(16)}," +
            "${buffer.getInt(cpuOffset + 0x88).toString(16)}; " +
            "sp: ${buffer.getInt(cpuOffset + 0x38 + 15 * 4).toString(16)}; " +
            "r0: ${buffer.getInt(cpuOffset + 0x38).toString(16)}"
    }

    private fun snapshotKeyboard0EventCount(snapshot: ByteArray): Int {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_KEYBOARD0_DEVICE_KIND) {
                return buffer.getInt(payloadOffset + 12)
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a keyboard0 device record")
    }

    private fun snapshotWithTimer0GameTicks(
        snapshot: ByteArray,
        gameTicks: Long,
    ): ByteArray {
        val editedSnapshot = snapshot.copyOf()
        val buffer = ByteBuffer.wrap(editedSnapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_TIMER0_DEVICE_KIND) {
                assertEquals(K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE, payloadSize)
                buffer.putLong(payloadOffset, gameTicks)
                return editedSnapshot
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a timer0 device record")
    }

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(
            snapshot = snapshot,
            start = K16_TERMINAL_CELLS_ADDR,
            size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS,
        ).map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun runK16Tool(vararg args: String) {
        val (exitCode, output) = runK16ToolProcess(*args)
        assertEquals(0, exitCode, "k16 ${args.joinToString(" ")} failed:\n$output")
    }

    private fun runK16ToolExpectFailure(vararg args: String) {
        val (exitCode, output) = runK16ToolProcess(*args)
        assertTrue(exitCode != 0, "k16 ${args.joinToString(" ")} should fail:\n$output")
    }

    private fun runK16ToolProcess(vararg args: String): Pair<Int, String> {
        val executable = k16ToolExecutable()
        assertTrue(Files.isExecutable(executable), "K16 tool should be executable at $executable")
        val process =
            ProcessBuilder(listOf(executable.toString()) + args.toList())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        val exitCode = process.waitFor()
        return exitCode to output
    }

    private fun k16ToolExecutable(): Path {
        return Path.of("../../../.toolchain/build/cargo/k16-tools/release/k16")
    }
}

private class FirmwareCapturingDisplayNetworkBridge : DisplayNetworkBridge {
    private val payloads = CopyOnWriteArrayList<ByteArray>()

    override fun isRetainedDisplayViewerAuthorized(
        playerUuid: UUID,
        deviceId: Int,
    ): Boolean = true

    override fun sendRetainedDisplayPayload(
        playerUuid: UUID,
        deviceId: Int,
        payload: ByteArray,
    ) {
        payloads += payload.copyOf()
    }

    fun sentPayloads(): List<ByteArray> = payloads.map(ByteArray::copyOf)
}

private const val K16_SNAPSHOT_CPU_RECORD_SIZE = 208
private const val K16_SNAPSHOT_DEVICE_HEADER_SIZE = 8
private const val K16_SNAPSHOT_TIMER0_DEVICE_KIND = 6
private const val K16_SNAPSHOT_KEYBOARD0_DEVICE_KIND = 7
private const val K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE = 8
