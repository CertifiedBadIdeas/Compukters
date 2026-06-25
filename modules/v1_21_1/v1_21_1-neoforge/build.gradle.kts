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

@file:Suppress("PropertyName")

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

plugins {
    idea
    alias(libs.plugins.v1211)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.metadataConvention)
}

val gameTest by sourceSets.creating

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

tasks.configureEach {
    if (name == "runGameTestServer") {
        dependsOn(gameTest.classesTaskName)
    }
}

loom {
    // Generic client / client2 / server runs are declared in the
    // `loom-runs-convention` precompiled script plugin (build-scripts).
    // Only neoforge-specific runs live here.
    runs {
        register("gameTestServer") {
            server()
            runDir("run/gameTestServer")
            property("neoforge.enableGameTest", "true")
            property("neoforge.enabledGameTestNamespaces", "compukterkraft,minecraft")
            property("neoforge.gameTestServer", "true")
            property("kotlinx.coroutines.debug", "off")
            ideConfigGenerated(true)
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.uiDsl.path))
            sourceSet("main", project(projects.v1211Common.path))
            sourceSet("main", project(projects.core.path))
            sourceSet("main", project(":native-runtime"))
            sourceSet(gameTest.name)
        }
    }
}

dependencies {
    common(project(path = projects.v1211Common.path, configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = projects.uiDsl.path)) { isTransitive = false }
    shadowBundle(project(path = projects.v1211Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    modImplementation(libs.geckolib.neoforge.v1211)

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v1211Common.path, configuration = "namedElements"))
}

val k16VmNativePlatform = currentK16VmNativePlatform()
val k16VmNativeLibrary =
    rootProject.layout.projectDirectory.file(".toolchain/build/cargo/k16-vm/debug/${k16VmNativePlatform.libraryName}")
val k16LlvmBuildRoot =
    rootProject.layout.projectDirectory.dir(
        providers.gradleProperty("k16LlvmBuildDir").orElse(".toolchain/build/llvm/k16-min").get(),
    )
val k16ClangExecutable = k16LlvmBuildRoot.file("bin/clang")
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareTestResources = layout.buildDirectory.dir("generated/k16-firmware-test-resources")
val generatedK16FirmwareArtifacts = layout.buildDirectory.dir("generated/k16-firmware-artifacts")
val generatedK16GuestTarget = layout.buildDirectory.dir("generated/k16-guest-target")
val generatedK16BiosTarget = generatedK16GuestTarget.map { it.dir("bios") }
val generatedK16BootTarget = generatedK16GuestTarget.map { it.dir("boot") }
val generatedK16KernelTarget = generatedK16GuestTarget.map { it.dir("kernel") }
val generatedK16ShellTarget = generatedK16GuestTarget.map { it.dir("shell") }
val generatedK16CSystemUnameTarget = generatedK16GuestTarget.map { it.dir("c-system-uname") }
val generatedK16CSystemLsTarget = generatedK16GuestTarget.map { it.dir("c-system-ls") }
val generatedK16CSystemCpTarget = generatedK16GuestTarget.map { it.dir("c-system-cp") }
val generatedK16CSystemMvTarget = generatedK16GuestTarget.map { it.dir("c-system-mv") }
val generatedK16CSystemStatTarget = generatedK16GuestTarget.map { it.dir("c-system-stat") }
val generatedK16CSystemWriteTarget = generatedK16GuestTarget.map { it.dir("c-system-write") }
val generatedK16CSystemRmTarget = generatedK16GuestTarget.map { it.dir("c-system-rm") }
val generatedK16CSystemMkdirTarget = generatedK16GuestTarget.map { it.dir("c-system-mkdir") }
val generatedK16CSystemRmdirTarget = generatedK16GuestTarget.map { it.dir("c-system-rmdir") }
val generatedK16SharedRuntimeTarget = generatedK16GuestTarget.map { it.dir("shared-runtime") }
val generatedK16SharedKraftTarget = generatedK16GuestTarget.map { it.dir("shared-kraft") }
val generatedK16RuntimeImportTestTarget = generatedK16GuestTarget.map { it.dir("runtime-import-test") }
val generatedK16CSystemCatTarget = generatedK16GuestTarget.map { it.dir("c-system-cat") }
val generatedK16AllocTestTarget = generatedK16GuestTarget.map { it.dir("alloc-test") }
val generatedK16ProcTestTarget = generatedK16GuestTarget.map { it.dir("proc-test") }
val generatedK16SyscallFaultTestTarget = generatedK16GuestTarget.map { it.dir("syscall-fault-test") }
val generatedK16UserFaultTestTarget = generatedK16GuestTarget.map { it.dir("user-fault-test") }
val k16FirmwareProfile =
    providers
        .gradleProperty("k16FirmwareProfile")
        .orElse("release")
val k16GuestManifest = rootProject.layout.projectDirectory.file("rust/guest/Cargo.toml")
val k16BiosManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/Cargo.toml")
val k16BiosSource = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/src/main.rs")
val k16BootManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/Cargo.toml")
val k16BootSource = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/src/main.rs")
val k16KernelManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-kernel/Cargo.toml")
val k16KernelSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-kernel/src")
val k16InitManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-init/Cargo.toml")
val k16InitSource = rootProject.layout.projectDirectory.file("rust/guest/k16-init/src/main.rs")
val k16ShellManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-shell/Cargo.toml")
val k16ShellSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-shell/src")
val k16SharedRuntimeManifest =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-runtime/Cargo.toml")
val k16SharedRuntimeSource =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-runtime/src/main.rs")
val k16SharedKraftManifest =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-kraft/Cargo.toml")
val k16SharedKraftSource =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-kraft/src/main.rs")
val k16RuntimeImportTestManifest =
    rootProject.layout.projectDirectory.file("rust/guest/k16-runtime-import-test/Cargo.toml")
val k16RuntimeImportTestSource =
    rootProject.layout.projectDirectory.file("rust/guest/k16-runtime-import-test/src/main.rs")
val k16CLibcIncludeSource = rootProject.layout.projectDirectory.dir("guest/c/libc/include")
val k16CLibcStartupSource = rootProject.layout.projectDirectory.file("guest/c/libc/crt0.c")
val k16CLibcSyscallSource = rootProject.layout.projectDirectory.file("guest/c/libc/syscalls.c")
val k16CSystemUnameSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/uname.c")
val k16CSystemLsSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/ls.c")
val k16CSystemCatSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/cat.c")
val k16CSystemCpSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/cp.c")
val k16CSystemMvSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/mv.c")
val k16CSystemStatSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/stat.c")
val k16CSystemWriteSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/write.c")
val k16CSystemRmSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/rm.c")
val k16CSystemMkdirSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/mkdir.c")
val k16CSystemRmdirSource = rootProject.layout.projectDirectory.file("guest/c/coreutils/rmdir.c")
val k16MotdSource = rootProject.layout.projectDirectory.file("guest/data/etc/motd")
val k16AllocTestManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-alloc-test/Cargo.toml")
val k16AllocTestSource = rootProject.layout.projectDirectory.file("rust/guest/k16-alloc-test/src/main.rs")
val k16ProcTestManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-proc-test/Cargo.toml")
val k16ProcTestSource = rootProject.layout.projectDirectory.file("rust/guest/k16-proc-test/src/main.rs")
val k16SyscallFaultTestManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-syscall-fault-test/Cargo.toml")
val k16SyscallFaultTestSource = rootProject.layout.projectDirectory.file("rust/guest/k16-syscall-fault-test/src/main.rs")
val k16UserFaultTestManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-user-fault-test/Cargo.toml")
val k16UserFaultTestSource = rootProject.layout.projectDirectory.file("rust/guest/k16-user-fault-test/src/main.rs")
val k16AbiManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-abi/Cargo.toml")
val k16AbiSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-abi/src")
val k16RtManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-rt/Cargo.toml")
val k16RtSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-rt/src")
val k16MemoryManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-memory/Cargo.toml")
val k16MemorySource = rootProject.layout.projectDirectory.dir("rust/guest/k16-memory/src")
val k16ImageManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-image/Cargo.toml")
val k16ImageSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-image/src")
val k16StorageManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-storage/Cargo.toml")
val k16StorageSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-storage/src")
val kraftStdManifest = rootProject.layout.projectDirectory.file("rust/guest/kraft-std/Cargo.toml")
val kraftStdSource = rootProject.layout.projectDirectory.dir("rust/guest/kraft-std/src")
val k16BootChainManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-boot-chain/Cargo.toml")
val k16BootChainSource = rootProject.layout.projectDirectory.dir("rust/guest/k16-boot-chain/src")
val k16HostToolsManifest = rootProject.layout.projectDirectory.file("rust/host/k16-tools/Cargo.toml")
val k16HostToolsSource = rootProject.layout.projectDirectory.dir("rust/host/k16-tools/src")
val k16RustTargetSpec = rootProject.layout.projectDirectory.file("tools/k16-unknown-kraftos.json")
val k16ToolchainConfig = rootProject.layout.projectDirectory.file("config/k16-toolchain.json")
val k16BiosFlashResource = generatedK16FirmwareResources.map { it.file("firmware/k16-bios.kflash") }
val k16BootArtifact = generatedK16FirmwareArtifacts.map { it.file("kernel-loader.kb") }
val k16KernelArtifact = generatedK16FirmwareArtifacts.map { it.file("display-ok.kx") }
val k16InitArtifact = generatedK16FirmwareArtifacts.map { it.file("init.kx") }
val k16ShellArtifact = generatedK16FirmwareArtifacts.map { it.file("shell.kx") }
val k16UnameArtifact = generatedK16FirmwareArtifacts.map { it.file("uname.kx") }
val k16LsArtifact = generatedK16FirmwareArtifacts.map { it.file("ls.kx") }
val k16CatArtifact = generatedK16FirmwareArtifacts.map { it.file("cat.kx") }
val k16CpArtifact = generatedK16FirmwareArtifacts.map { it.file("cp.kx") }
val k16MvArtifact = generatedK16FirmwareArtifacts.map { it.file("mv.kx") }
val k16StatArtifact = generatedK16FirmwareArtifacts.map { it.file("stat.kx") }
val k16WriteArtifact = generatedK16FirmwareArtifacts.map { it.file("write.kx") }
val k16RmArtifact = generatedK16FirmwareArtifacts.map { it.file("rm.kx") }
val k16MkdirArtifact = generatedK16FirmwareArtifacts.map { it.file("mkdir.kx") }
val k16RmdirArtifact = generatedK16FirmwareArtifacts.map { it.file("rmdir.kx") }
val k16SharedRuntimeArtifact = generatedK16FirmwareArtifacts.map { it.file("libk16rt.k16so") }
val k16SharedKraftArtifact = generatedK16FirmwareArtifacts.map { it.file("libkraft.k16so") }
val k16RuntimeImportTestArtifact = generatedK16FirmwareArtifacts.map { it.file("runtime-import-test.kx") }
val k16AllocTestArtifact = generatedK16FirmwareArtifacts.map { it.file("alloc-test.kx") }
val k16ProcTestArtifact = generatedK16FirmwareArtifacts.map { it.file("proc-test.kx") }
val k16SyscallFaultTestArtifact = generatedK16FirmwareArtifacts.map { it.file("syscall-fault-test.kx") }
val k16UserFaultTestArtifact = generatedK16FirmwareArtifacts.map { it.file("user-fault-test.kx") }
val k16BootMapArtifact = k16BootArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16KernelMapArtifact = k16KernelArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16InitMapArtifact = k16InitArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16ShellMapArtifact = k16ShellArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16UnameMapArtifact = k16UnameArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16LsMapArtifact = k16LsArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16CatMapArtifact = k16CatArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16CpMapArtifact = k16CpArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16MvMapArtifact = k16MvArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16StatMapArtifact = k16StatArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16WriteMapArtifact = k16WriteArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16RmMapArtifact = k16RmArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16MkdirMapArtifact = k16MkdirArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16RmdirMapArtifact = k16RmdirArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16SharedRuntimeMapArtifact =
    k16SharedRuntimeArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16SharedKraftMapArtifact =
    k16SharedKraftArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16RuntimeImportTestMapArtifact =
    k16RuntimeImportTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16AllocTestMapArtifact =
    k16AllocTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16ProcTestMapArtifact = k16ProcTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16SyscallFaultTestMapArtifact =
    k16SyscallFaultTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16UserFaultTestMapArtifact =
    k16UserFaultTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16ProductionUserlandMapArtifacts =
    listOf(
        k16InitMapArtifact,
        k16ShellMapArtifact,
        k16UnameMapArtifact,
        k16LsMapArtifact,
        k16CatMapArtifact,
        k16CpMapArtifact,
        k16MvMapArtifact,
        k16StatMapArtifact,
        k16WriteMapArtifact,
        k16RmMapArtifact,
        k16MkdirMapArtifact,
        k16RmdirMapArtifact,
    )
val k16SharedRuntimeMapArtifacts =
    listOf(
        k16SharedRuntimeMapArtifact,
        k16SharedKraftMapArtifact,
    )
val k16DevelopmentOnlyMapArtifacts =
    listOf(
        k16AllocTestMapArtifact,
        k16RuntimeImportTestMapArtifact,
        k16ProcTestMapArtifact,
    )
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }
val k16DevelopmentStorage0Resource =
    generatedK16FirmwareTestResources.map { it.file("firmware/k16-system-storage0-dev.kv") }

val k16ProductionStorageEntries =
    listOf(
        "/bin/init.kx" to k16InitArtifact,
        "/bin/shell.kx" to k16ShellArtifact,
        "/bin/uname.kx" to k16UnameArtifact,
        "/bin/ls.kx" to k16LsArtifact,
        "/bin/cat.kx" to k16CatArtifact,
        "/bin/cp.kx" to k16CpArtifact,
        "/bin/mv.kx" to k16MvArtifact,
        "/bin/stat.kx" to k16StatArtifact,
        "/bin/write.kx" to k16WriteArtifact,
        "/bin/rm.kx" to k16RmArtifact,
        "/bin/mkdir.kx" to k16MkdirArtifact,
        "/bin/rmdir.kx" to k16RmdirArtifact,
        "/etc/motd" to k16MotdSource,
    )
val k16DevelopmentOnlyStorageEntries =
    listOf(
        "/bin/alloc-test.kx" to k16AllocTestArtifact,
        "/bin/runtime-import-test.kx" to k16RuntimeImportTestArtifact,
        "/bin/proc-test.kx" to k16ProcTestArtifact,
    )
val k16SharedRuntimeStorageEntries =
    listOf(
        "/lib/libk16rt.k16so" to k16SharedRuntimeArtifact,
        "/lib/libkraft.k16so" to k16SharedKraftArtifact,
    )

fun artifactFile(artifact: Any): File =
    when (artifact) {
        is File -> artifact
        is RegularFile -> artifact.asFile
        is Provider<*> -> (artifact.get() as RegularFile).asFile
        else -> error("Unsupported K16 storage artifact type: ${artifact::class.qualifiedName}")
    }

fun deleteK16RustBinOutputs(
    targetDir: File,
    binName: String,
    profile: String,
) {
    val profileDir = k16RustBinProfileDir(targetDir, profile)
    profileDir.resolve(binName).delete()
    profileDir.resolve("$binName.d").delete()
    val cargoBinPrefix = cargoK16RustBinArtifactPrefix(binName)
    fun deleteMatchingEntries(
        directory: File,
        matches: (String) -> Boolean,
    ) {
        directory
            .listFiles()
            ?.filter { matches(it.name) }
            ?.forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
    }
    val depsDir = profileDir.resolve("deps")
    deleteMatchingEntries(depsDir) {
        it.startsWith("$binName-") ||
            it.startsWith("$cargoBinPrefix-")
    }
    deleteMatchingEntries(profileDir.resolve(".fingerprint")) {
        it.startsWith("$binName-") ||
            it.startsWith("$cargoBinPrefix-")
    }
    deleteMatchingEntries(profileDir.resolve("incremental")) {
        it.startsWith("$binName-") ||
            it.startsWith("$cargoBinPrefix-")
    }
}

fun copyK16RustBinOutput(
    targetDir: File,
    binName: String,
    output: File,
    profile: String,
) {
    val artifact = findCargoK16RustBinArtifact(targetDir, binName, profile)
    output.parentFile.mkdirs()
    artifact.copyTo(output, overwrite = true)
}

fun findCargoK16RustBinArtifact(
    targetDir: File,
    binName: String,
    profile: String,
): File {
    val cargoBinPrefix = cargoK16RustBinArtifactPrefix(binName)
    val depsDir = k16RustBinProfileDir(targetDir, profile).resolve("deps")
    val artifacts =
        depsDir
            .listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("$cargoBinPrefix-") &&
                    !it.name.endsWith(".d")
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    check(artifacts.size == 1) {
        "Expected exactly one linked K16 Rust $profile bin artifact for $binName in $depsDir, found ${artifacts.size}"
    }
    val artifact = artifacts.single()
    check(artifact.isFile) {
        "Expected linked K16 Rust $profile bin artifact for $binName at $artifact"
    }
    return artifact
}

fun cargoK16RustBinArtifactPrefix(binName: String): String {
    return binName.replace('-', '_')
}

fun k16FirmwareProfileName(): String {
    val profile = k16FirmwareProfile.get()
    check(profile == "debug" || profile == "release") {
        "k16FirmwareProfile must be 'debug' or 'release', got: $profile"
    }
    return profile
}

fun k16CargoProfileArgs(profile: String): List<String> =
    when (profile) {
        "debug" -> emptyList()
        "release" -> listOf("--release")
        else -> error("Unsupported K16 firmware profile: $profile")
    }

fun org.gradle.api.Task.inputsK16RuntimeCrates() {
    inputs.file(k16AbiManifest)
    inputs.dir(k16AbiSource)
    inputs.file(k16RtManifest)
    inputs.dir(k16RtSource)
    inputs.file(k16MemoryManifest)
    inputs.dir(k16MemorySource)
}

fun org.gradle.api.Task.inputsKraftStdCrate() {
    inputs.file(kraftStdManifest)
    inputs.dir(kraftStdSource)
}

fun org.gradle.api.Task.inputsK16KernelCrates() {
    inputs.file(k16ImageManifest)
    inputs.dir(k16ImageSource)
    inputs.file(k16StorageManifest)
    inputs.dir(k16StorageSource)
    inputs.file(k16BootChainManifest)
    inputs.dir(k16BootChainSource)
}

fun k16RustBinProfileDir(
    targetDir: File,
    profile: String,
): File = targetDir.resolve("k16-unknown-kraftos/$profile")

fun Project.compileK16GuestRustBin(
    manifest: File,
    targetDir: File,
    binName: String,
    k16Target: String,
    output: File,
    mapOutput: File? = null,
    buildStd: String = "core",
    buildStdFeatures: String? = null,
    extraRuntimeLinkArgs: List<String> = emptyList(),
    includeCpuHelpersForSharedObject: Boolean = false,
) {
    val toolchain = resolveK16Toolchain()
    val profile = k16FirmwareProfileName()
    val cpuHelpers = targetDir.resolve("k16-cpu-helpers.o")
    val startup = targetDir.resolve("k16-startup.o")
    output.parentFile.mkdirs()
    if (mapOutput != null) {
        mapOutput.parentFile.mkdirs()
        mapOutput.delete()
    }
    cpuHelpers.parentFile.mkdirs()
    deleteK16RustBinOutputs(targetDir, binName, profile)
    fun buildRuntimeObject(
        runtimeObject: String,
        output: File,
        target: String? = null,
    ) {
        val helperCommand =
            listOf(
                "cargo",
                "run",
                "--quiet",
                "--offline",
                "--manifest-path",
                k16HostToolsManifest.asFile.absolutePath,
                "--bin",
                "k16",
                "--",
                "runtime",
                runtimeObject,
            ) +
                buildList {
                    if (target != null) {
                        add("--target")
                        add(target)
                    }
                    add("-o")
                    add(output.absolutePath)
                }
        val helperProcessBuilder =
            ProcessBuilder(helperCommand)
                .directory(projectDir)
                .inheritIO()
        val helperExitCode = helperProcessBuilder.start().waitFor()
        check(helperExitCode == 0) {
            "K16 runtime object build for $runtimeObject failed with exit code $helperExitCode"
        }
    }
    val needsCpuHelpers = k16Target != "shared-object" || includeCpuHelpersForSharedObject
    if (needsCpuHelpers) {
        buildRuntimeObject("k16-cpu-helpers", cpuHelpers)
    }
    val needsStartup = k16Target == "program" || k16Target == "program-dynamic"
    if (needsStartup) {
        buildRuntimeObject("k16-startup", startup, target = k16Target)
    }
    val runtimeLinkArgs =
        buildList {
            if (needsStartup) {
                add("-C link-arg=${startup.absolutePath}")
            }
            if (needsCpuHelpers) {
                add("-C link-arg=${cpuHelpers.absolutePath}")
            }
            if (mapOutput != null) {
                add("-C link-arg=--map")
                add("-C link-arg=${mapOutput.absolutePath}")
            }
            addAll(extraRuntimeLinkArgs)
        }.joinToString(" ")
    val command =
        listOf(toolchain.cargo.absolutePath, "rustc") +
            k16CargoProfileArgs(profile) +
            listOf(
                "-Zbuild-std=$buildStd",
            ) +
            buildList {
                if (buildStdFeatures != null) {
                    add("-Zbuild-std-features=$buildStdFeatures")
                }
            } +
            listOf(
                "-Zjson-target-spec",
                "--manifest-path",
                manifest.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                binName,
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                targetDir.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "opt-level=z",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
    val processBuilder =
        ProcessBuilder(command)
            .directory(projectDir)
            .inheritIO()
    processBuilder.environment()["RUSTC"] = toolchain.rustc.absolutePath
    processBuilder.environment()["RUSTC_BOOTSTRAP"] = "1"
    processBuilder.environment()["RUSTFLAGS"] =
        "-C linker=${toolchain.linker.absolutePath} $runtimeLinkArgs -C link-arg=--k16-target=$k16Target -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no"
    val exitCode = processBuilder.start().waitFor()
    check(exitCode == 0) {
        "K16 Rust firmware build for $binName failed with exit code $exitCode"
    }
    copyK16RustBinOutput(
        targetDir = targetDir,
        binName = binName,
        output = output,
        profile = profile,
    )
}

fun Project.compileK16GuestCProgram(
    targetDir: File,
    output: File,
    mapOutput: File,
    includeDir: File,
    startupSource: File,
    sources: List<File>,
    dylibs: List<File>,
) {
    val toolchain = resolveK16Toolchain()
    val clang = k16ClangExecutable.asFile
    check(clang.isFile && clang.canExecute()) {
        "K16 clang is missing or not executable at $clang; run buildK16Llvm through ./gradlew-sandbox-dev"
    }

    targetDir.mkdirs()
    output.parentFile.mkdirs()
    mapOutput.parentFile.mkdirs()
    output.delete()
    mapOutput.delete()

    val startupObject = targetDir.resolve("k16-startup.o")
    val startupCommand =
        listOf(
            toolchain.cli.absolutePath,
            "runtime",
            "k16-startup",
            "--target",
            "program-dynamic",
            "-o",
            startupObject.absolutePath,
        )
    val startupExitCode =
        ProcessBuilder(startupCommand)
            .directory(projectDir)
            .inheritIO()
            .start()
            .waitFor()
    check(startupExitCode == 0) {
        "K16 C startup object build failed with exit code $startupExitCode: ${startupCommand.joinToString(" ")}"
    }

    val objectFiles =
        (listOf(startupSource) + sources).map { source ->
            val objectFile = targetDir.resolve("${source.nameWithoutExtension}.o")
            objectFile.delete()
            val command =
                listOf(
                    clang.absolutePath,
                    "--target=k16",
                    "-ffreestanding",
                    "-fno-builtin",
                    "-fno-stack-protector",
                    "-nostdlib",
                    "-Oz",
                    "-I",
                    includeDir.absolutePath,
                ) +
                    buildList {
                        if (source != startupSource) {
                            add("-Dmain=kraft_main")
                        }
                        add("-c")
                        add(source.absolutePath)
                        add("-o")
                        add(objectFile.absolutePath)
                    }
            val exitCode =
                ProcessBuilder(command)
                    .directory(projectDir)
                    .inheritIO()
                    .start()
                    .waitFor()
            check(exitCode == 0) {
                "K16 C compile failed with exit code $exitCode: ${command.joinToString(" ")}"
            }
            objectFile
        }

    val linkCommand =
        buildList {
            add(toolchain.cli.absolutePath)
            add("link")
            add("--target")
            add("program-dynamic")
            add("--map")
            add(mapOutput.absolutePath)
            dylibs.forEach { dylib ->
                add("--dylib")
                add(dylib.absolutePath)
            }
            add(startupObject.absolutePath)
            objectFiles.forEach { objectFile ->
                add(objectFile.absolutePath)
            }
            add("-o")
            add(output.absolutePath)
        }
    val linkExitCode =
        ProcessBuilder(linkCommand)
            .directory(projectDir)
            .inheritIO()
            .start()
            .waitFor()
    check(linkExitCode == 0) {
        "K16 C link failed with exit code $linkExitCode: ${linkCommand.joinToString(" ")}"
    }
}

val linkK16BiosFlash =
    tasks.register("linkK16BiosFlash") {
        description = "Compiles and links the bundled Rust K16 BIOS bin crate into a raw BIOS flash resource."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BiosManifest)
        inputs.file(k16BiosSource)
        inputs.file(k16BootChainManifest)
        inputs.dir(k16BootChainSource)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16BiosFlashResource)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16BiosManifest.asFile,
                targetDir = generatedK16BiosTarget.get().asFile,
                binName = "k16-bios",
                k16Target = "bios",
                output = k16BiosFlashResource.get().asFile,
            )
        }
    }

val compileK16SystemBoot =
    tasks.register("compileK16SystemBoot") {
        description = "Compiles and links the bundled Rust K16 bootloader bin crate into a K16E boot artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BootManifest)
        inputs.file(k16BootSource)
        inputs.file(k16BootChainManifest)
        inputs.dir(k16BootChainSource)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16BootArtifact)
        outputs.file(k16BootMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16BootManifest.asFile,
                targetDir = generatedK16BootTarget.get().asFile,
                binName = "k16-boot",
                k16Target = "boot",
                output = k16BootArtifact.get().asFile,
                mapOutput = k16BootMapArtifact.get(),
            )
        }
    }

val compileK16SystemKernel =
    tasks.register("compileK16SystemKernel") {
        description = "Compiles and links the bundled Rust K16 kernel bin crate into a K16E kernel artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16KernelManifest)
        inputs.dir(k16KernelSource)
        inputsK16RuntimeCrates()
        inputsK16KernelCrates()
        inputs.file(k16RustTargetSpec)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16KernelArtifact)
        outputs.file(k16KernelMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16KernelManifest.asFile,
                targetDir = generatedK16KernelTarget.get().asFile,
                binName = "k16-kernel",
                k16Target = "kernel",
                output = k16KernelArtifact.get().asFile,
                mapOutput = k16KernelMapArtifact.get(),
            )
        }
    }

val compileK16SystemInit =
    tasks.register("compileK16SystemInit") {
        description = "Compiles and links the bundled Rust K16 init launcher into the boot K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16InitManifest)
        inputs.file(k16InitSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16InitArtifact)
        outputs.file(k16InitMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16InitManifest.asFile,
                targetDir = generatedK16GuestTarget.get().dir("init").asFile,
                binName = "k16-init",
                k16Target = "program-dynamic",
                output = k16InitArtifact.get().asFile,
                mapOutput = k16InitMapArtifact.get(),
            )
        }
    }

val compileK16SystemShell =
    tasks.register("compileK16SystemShell") {
        description = "Compiles and links the bundled Rust K16 shell program into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16ShellManifest)
        inputs.dir(k16ShellSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16ShellArtifact)
        outputs.file(k16ShellMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16ShellManifest.asFile,
                targetDir = generatedK16ShellTarget.get().asFile,
                binName = "k16-shell",
                k16Target = "program-dynamic",
                output = k16ShellArtifact.get().asFile,
                mapOutput = k16ShellMapArtifact.get(),
                buildStd = "core,alloc",
            )
        }
    }

val compileK16SystemUname =
    tasks.register("compileK16SystemUname") {
        description = "Compiles and links the bundled C K16 uname utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemUnameSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16UnameArtifact)
        outputs.file(k16UnameMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemUnameTarget.get().asFile,
                output = k16UnameArtifact.get().asFile,
                mapOutput = k16UnameMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemUnameSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemLs =
    tasks.register("compileK16SystemLs") {
        description = "Compiles and links the bundled C K16 ls utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemLsSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16LsArtifact)
        outputs.file(k16LsMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemLsTarget.get().asFile,
                output = k16LsArtifact.get().asFile,
                mapOutput = k16LsMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemLsSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemCat =
    tasks.register("compileK16SystemCat") {
        description = "Compiles and links the bundled C K16 cat utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemCatSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16CatArtifact)
        outputs.file(k16CatMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemCatTarget.get().asFile,
                output = k16CatArtifact.get().asFile,
                mapOutput = k16CatMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemCatSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemCp =
    tasks.register("compileK16SystemCp") {
        description = "Compiles and links the bundled C K16 cp utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemCpSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16CpArtifact)
        outputs.file(k16CpMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemCpTarget.get().asFile,
                output = k16CpArtifact.get().asFile,
                mapOutput = k16CpMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemCpSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemMv =
    tasks.register("compileK16SystemMv") {
        description = "Compiles and links the bundled C K16 mv utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemMvSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16MvArtifact)
        outputs.file(k16MvMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemMvTarget.get().asFile,
                output = k16MvArtifact.get().asFile,
                mapOutput = k16MvMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemMvSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemStat =
    tasks.register("compileK16SystemStat") {
        description = "Compiles and links the bundled C K16 stat utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemStatSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16StatArtifact)
        outputs.file(k16StatMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemStatTarget.get().asFile,
                output = k16StatArtifact.get().asFile,
                mapOutput = k16StatMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemStatSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemWrite =
    tasks.register("compileK16SystemWrite") {
        description = "Compiles and links the bundled C K16 write utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemWriteSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16WriteArtifact)
        outputs.file(k16WriteMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemWriteTarget.get().asFile,
                output = k16WriteArtifact.get().asFile,
                mapOutput = k16WriteMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemWriteSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemRm =
    tasks.register("compileK16SystemRm") {
        description = "Compiles and links the bundled C K16 rm utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemRmSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16RmArtifact)
        outputs.file(k16RmMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemRmTarget.get().asFile,
                output = k16RmArtifact.get().asFile,
                mapOutput = k16RmMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemRmSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemMkdir =
    tasks.register("compileK16SystemMkdir") {
        description = "Compiles and links the bundled C K16 mkdir utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemMkdirSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16MkdirArtifact)
        outputs.file(k16MkdirMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemMkdirTarget.get().asFile,
                output = k16MkdirArtifact.get().asFile,
                mapOutput = k16MkdirMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemMkdirSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SystemRmdir =
    tasks.register("compileK16SystemRmdir") {
        description = "Compiles and links the bundled C K16 rmdir utility into an imported dynamic K16E program artifact."
        group = "k16"
        inputs.dir(k16CLibcIncludeSource)
        inputs.file(k16CLibcStartupSource)
        inputs.file(k16CLibcSyscallSource)
        inputs.file(k16CSystemRmdirSource)
        inputs.file(k16SharedKraftArtifact)
        inputs.file(k16ClangExecutable)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16RmdirArtifact)
        outputs.file(k16RmdirMapArtifact)
        dependsOn(rootProject.tasks.named("buildK16Llvm"))
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedKraft")

        doLast {
            project.compileK16GuestCProgram(
                targetDir = generatedK16CSystemRmdirTarget.get().asFile,
                output = k16RmdirArtifact.get().asFile,
                mapOutput = k16RmdirMapArtifact.get(),
                includeDir = k16CLibcIncludeSource.asFile,
                startupSource = k16CLibcStartupSource.asFile,
                sources = listOf(k16CLibcSyscallSource.asFile, k16CSystemRmdirSource.asFile),
                dylibs = listOf(k16SharedKraftArtifact.get().asFile),
            )
        }
    }

val compileK16SharedRuntime =
    tasks.register("compileK16SharedRuntime") {
        description = "Compiles and links the bundled K16 shared runtime provider into a K16E shared object."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16SharedRuntimeManifest)
        inputs.file(k16SharedRuntimeSource)
        inputsK16RuntimeCrates()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16SharedRuntimeArtifact)
        outputs.file(k16SharedRuntimeMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16SharedRuntimeManifest.asFile,
                targetDir = generatedK16SharedRuntimeTarget.get().asFile,
                binName = "k16-shared-runtime",
                k16Target = "shared-object",
                output = k16SharedRuntimeArtifact.get().asFile,
                mapOutput = k16SharedRuntimeMapArtifact.get(),
            )
        }
    }

val compileK16SharedKraft =
    tasks.register("compileK16SharedKraft") {
        description = "Compiles and links the bundled Kraft shared userland library into a K16E shared object."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16SharedKraftManifest)
        inputs.file(k16SharedKraftSource)
        inputsK16RuntimeCrates()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16SharedKraftArtifact)
        outputs.file(k16SharedKraftMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16SharedKraftManifest.asFile,
                targetDir = generatedK16SharedKraftTarget.get().asFile,
                binName = "k16-shared-kraft",
                k16Target = "shared-object",
                output = k16SharedKraftArtifact.get().asFile,
                mapOutput = k16SharedKraftMapArtifact.get(),
                includeCpuHelpersForSharedObject = true,
            )
        }
    }

val compileK16RuntimeImportTest =
    tasks.register("compileK16RuntimeImportTest") {
        description = "Compiles and links the bundled K16 runtime import test into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16RuntimeImportTestManifest)
        inputs.file(k16RuntimeImportTestSource)
        inputsK16RuntimeCrates()
        inputs.file(k16SharedRuntimeArtifact)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16RuntimeImportTestArtifact)
        outputs.file(k16RuntimeImportTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        dependsOn("compileK16SharedRuntime")

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16RuntimeImportTestManifest.asFile,
                targetDir = generatedK16RuntimeImportTestTarget.get().asFile,
                binName = "k16-runtime-import-test",
                k16Target = "program-dynamic",
                output = k16RuntimeImportTestArtifact.get().asFile,
                mapOutput = k16RuntimeImportTestMapArtifact.get(),
                extraRuntimeLinkArgs =
                    listOf(
                        "-C link-arg=--dylib",
                        "-C link-arg=${k16SharedRuntimeArtifact.get().asFile.absolutePath}",
                    ),
            )
        }
    }

val compileK16SystemAllocTest =
    tasks.register("compileK16SystemAllocTest") {
        description = "Compiles and links the bundled Rust K16 alloc test utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16AllocTestManifest)
        inputs.file(k16AllocTestSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16AllocTestArtifact)
        outputs.file(k16AllocTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16AllocTestManifest.asFile,
                targetDir = generatedK16AllocTestTarget.get().asFile,
                binName = "k16-alloc-test",
                k16Target = "program-dynamic",
                output = k16AllocTestArtifact.get().asFile,
                mapOutput = k16AllocTestMapArtifact.get(),
                buildStd = "core,alloc",
            )
        }
    }

val compileK16SystemProcTest =
    tasks.register("compileK16SystemProcTest") {
        description = "Compiles and links the bundled Rust K16 process test utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16ProcTestManifest)
        inputs.file(k16ProcTestSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16ProcTestArtifact)
        outputs.file(k16ProcTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16ProcTestManifest.asFile,
                targetDir = generatedK16ProcTestTarget.get().asFile,
                binName = "k16-proc-test",
                k16Target = "program-dynamic",
                output = k16ProcTestArtifact.get().asFile,
                mapOutput = k16ProcTestMapArtifact.get(),
            )
        }
    }

val compileK16UserFaultTest =
    tasks.register("compileK16UserFaultTest") {
        description = "Compiles and links the K16 user fault test fixture into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16UserFaultTestManifest)
        inputs.file(k16UserFaultTestSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16UserFaultTestArtifact)
        outputs.file(k16UserFaultTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16UserFaultTestManifest.asFile,
                targetDir = generatedK16UserFaultTestTarget.get().asFile,
                binName = "k16-user-fault-test",
                k16Target = "program-dynamic",
                output = k16UserFaultTestArtifact.get().asFile,
                mapOutput = k16UserFaultTestMapArtifact.get(),
            )
        }
    }

val compileK16SyscallFaultTest =
    tasks.register("compileK16SyscallFaultTest") {
        description = "Compiles and links the K16 syscall pointer fault test fixture into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16SyscallFaultTestManifest)
        inputs.file(k16SyscallFaultTestSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16SyscallFaultTestArtifact)
        outputs.file(k16SyscallFaultTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16SyscallFaultTestManifest.asFile,
                targetDir = generatedK16SyscallFaultTestTarget.get().asFile,
                binName = "k16-syscall-fault-test",
                k16Target = "program-dynamic",
                output = k16SyscallFaultTestArtifact.get().asFile,
                mapOutput = k16SyscallFaultTestMapArtifact.get(),
            )
        }
    }

val createK16SystemStorage0 =
    tasks.register<Exec>("createK16SystemStorage0") {
        description = "Creates the bundled K16 system storage0 volume resource."
        group = "k16"
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        outputs.file(k16SystemStorage0Resource)

        doFirst {
            val toolchain = resolveK16Toolchain()
            k16SystemStorage0Resource.get().asFile.parentFile.mkdirs()
            commandLine(
                toolchain.cli.absolutePath,
                "volume",
                "init",
                k16SystemStorage0Resource.get().asFile.absolutePath,
                "--size",
                "1048576",
            )
        }
    }

val putK16SystemStorage0Boot =
    tasks.register<Exec>("putK16SystemStorage0Boot") {
        description = "Writes the bundled K16 bootloader into the system storage0 volume resource."
        group = "k16"
        dependsOn(createK16SystemStorage0, compileK16SystemBoot)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16BootArtifact)

        doFirst {
            val toolchain = resolveK16Toolchain()
            commandLine(
                toolchain.cli.absolutePath,
                "volume",
                "put-boot",
                k16SystemStorage0Resource.get().asFile.absolutePath,
                k16BootArtifact.get().asFile.absolutePath,
            )
        }
    }

val compileK16SystemStorage0 =
    tasks.register<Exec>("compileK16SystemStorage0") {
        description = "Writes the bundled K16 kernel into the system storage0 volume resource."
        group = "k16"
        dependsOn(putK16SystemStorage0Boot, compileK16SystemKernel)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16BootArtifact)
        inputs.file(k16KernelArtifact)
        outputs.file(k16SystemStorage0Resource)

        doFirst {
            val toolchain = resolveK16Toolchain()
            commandLine(
                toolchain.cli.absolutePath,
                "volume",
                "put-kernel",
                k16SystemStorage0Resource.get().asFile.absolutePath,
                k16KernelArtifact.get().asFile.absolutePath,
            )
        }
    }

val putK16SystemStorage0Init =
    tasks.register("putK16SystemStorage0Init") {
        description = "Writes the bundled K16 userland layout into ROOT K16FS /bin, /lib, and /etc."
        group = "k16"
        dependsOn(compileK16SystemStorage0, compileK16SystemInit, compileK16SystemShell, compileK16SystemUname, compileK16SystemLs, compileK16SystemCat, compileK16SystemCp, compileK16SystemMv, compileK16SystemStat, compileK16SystemWrite, compileK16SystemRm, compileK16SystemMkdir, compileK16SystemRmdir, compileK16SharedRuntime, compileK16SharedKraft)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        (k16ProductionStorageEntries + k16SharedRuntimeStorageEntries).forEach { (_, artifact) ->
            inputs.file(artifact)
        }
        outputs.file(k16SystemStorage0Resource)

        doLast {
            val toolchain = resolveK16Toolchain()
            val rootPartition = temporaryDir.resolve("root.kfs")
            fun runK16Command(vararg args: String) {
                val command = listOf(toolchain.cli.absolutePath) + args.toList()
                val exitCode =
                    ProcessBuilder(command)
                        .directory(projectDir)
                        .inheritIO()
                        .start()
                        .waitFor()
                check(exitCode == 0) {
                    "K16 init storage command failed with exit code $exitCode: ${command.joinToString(" ")}"
                }
            }
            fun putStorageEntry(
                guestPath: String,
                artifact: Any,
            ) {
                runK16Command(
                    "fs",
                    "kfs",
                    "put",
                    rootPartition.absolutePath,
                    guestPath,
                    artifactFile(artifact).absolutePath,
                )
            }
            runK16Command(
                "volume",
                "extract-partition",
                k16SystemStorage0Resource.get().asFile.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "mkdir",
                rootPartition.absolutePath,
                "/bin",
            )
            runK16Command(
                "fs",
                "kfs",
                "mkdir",
                rootPartition.absolutePath,
                "/lib",
            )
            runK16Command(
                "fs",
                "kfs",
                "mkdir",
                rootPartition.absolutePath,
                "/etc",
            )
            (k16ProductionStorageEntries + k16SharedRuntimeStorageEntries).forEach { (guestPath, artifact) ->
                putStorageEntry(guestPath, artifact)
            }
            runK16Command(
                "volume",
                "replace-partition",
                k16SystemStorage0Resource.get().asFile.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
        }
    }

sourceSets.main {
    resources.srcDir(generatedK16FirmwareResources)
}

sourceSets.test {
    resources.srcDir(generatedK16FirmwareTestResources)
}

val putK16DevelopmentStorage0TestPrograms =
    tasks.register("putK16DevelopmentStorage0TestPrograms") {
        description = "Creates the development K16 storage0 image with test programs in ROOT K16FS /bin."
        group = "k16"
        dependsOn(putK16SystemStorage0Init, compileK16SystemAllocTest, compileK16SystemProcTest, compileK16RuntimeImportTest)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16SystemStorage0Resource)
        k16DevelopmentOnlyStorageEntries.forEach { (_, artifact) ->
            inputs.file(artifact)
        }
        outputs.file(k16DevelopmentStorage0Resource)

        doLast {
            val toolchain = resolveK16Toolchain()
            val devStorage = k16DevelopmentStorage0Resource.get().asFile
            val rootPartition = temporaryDir.resolve("root.kfs")
            fun runK16Command(vararg args: String) {
                val command = listOf(toolchain.cli.absolutePath) + args.toList()
                val exitCode =
                    ProcessBuilder(command)
                        .directory(projectDir)
                        .inheritIO()
                        .start()
                        .waitFor()
                check(exitCode == 0) {
                    "K16 dev storage command failed with exit code $exitCode: ${command.joinToString(" ")}"
                }
            }
            fun putStorageEntry(
                guestPath: String,
                artifact: Any,
            ) {
                runK16Command(
                    "fs",
                    "kfs",
                    "put",
                    rootPartition.absolutePath,
                    guestPath,
                    artifactFile(artifact).absolutePath,
                )
            }

            devStorage.parentFile.mkdirs()
            k16SystemStorage0Resource.get().asFile.copyTo(devStorage, overwrite = true)
            runK16Command(
                "volume",
                "extract-partition",
                devStorage.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
            k16DevelopmentOnlyStorageEntries.forEach { (guestPath, artifact) ->
                putStorageEntry(guestPath, artifact)
            }
            runK16Command(
                "volume",
                "replace-partition",
                devStorage.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
        }
    }

val reportK16UserlandSize =
    tasks.register("reportK16UserlandSize") {
        description = "Reports K16 production and development storage and userland sizes."
        group = "k16"
        inputs.files(k16ProductionUserlandMapArtifacts)
        inputs.files(k16SharedRuntimeMapArtifacts)
        inputs.files(k16DevelopmentOnlyMapArtifacts)
        inputs.file(k16SystemStorage0Resource)
        inputs.file(k16DevelopmentStorage0Resource)
        dependsOn(putK16SystemStorage0Init, putK16DevelopmentStorage0TestPrograms)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            val toolchain = resolveK16Toolchain()
            val productionStorage = k16SystemStorage0Resource.get().asFile
            val developmentStorage = k16DevelopmentStorage0Resource.get().asFile
            val productionStorageBytes = productionStorage.length()
            val developmentStorageBytes = developmentStorage.length()
            println("storage_image name=production bytes=$productionStorageBytes path=${productionStorage.absolutePath}")
            println("storage_image name=development bytes=$developmentStorageBytes path=${developmentStorage.absolutePath}")
            println("storage_image_delta name=development_minus_production bytes=${developmentStorageBytes - productionStorageBytes}")

            fun storageEntriesBytes(entries: List<Pair<String, Any>>): Long =
                entries.sumOf { (_, artifact) -> artifactFile(artifact).length() }

            val productionEntryBytes = storageEntriesBytes(k16ProductionStorageEntries)
            val sharedRuntimeEntryBytes = storageEntriesBytes(k16SharedRuntimeStorageEntries)
            val developmentOnlyEntryBytes = storageEntriesBytes(k16DevelopmentOnlyStorageEntries)
            println("storage_entries group=production files=${k16ProductionStorageEntries.size} bytes=$productionEntryBytes")
            println("storage_entries group=shared_runtime files=${k16SharedRuntimeStorageEntries.size} bytes=$sharedRuntimeEntryBytes")
            println("storage_entries group=development_only files=${k16DevelopmentOnlyStorageEntries.size} bytes=$developmentOnlyEntryBytes")
            println(
                "storage_entries group=development_total files=${k16ProductionStorageEntries.size + k16SharedRuntimeStorageEntries.size + k16DevelopmentOnlyStorageEntries.size} " +
                    "bytes=${productionEntryBytes + sharedRuntimeEntryBytes + developmentOnlyEntryBytes}",
            )

            fun runK16SizeReport(mapArtifacts: List<Provider<File>>) {
                val args = mutableListOf(toolchain.cli.absolutePath)
                args.add("size-report")
                mapArtifacts.forEach { mapArtifact ->
                    args.add(mapArtifact.get().absolutePath)
                }
                val processBuilder =
                    ProcessBuilder(args)
                        .directory(projectDir)
                val process = processBuilder.start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                print(stdout)
                print(stderr)
                check(exitCode == 0) {
                    "K16 userland size report failed with exit code $exitCode"
                }
            }

            println("map_section name=production_userland")
            runK16SizeReport(k16ProductionUserlandMapArtifacts)
            println("map_section name=shared_runtime")
            runK16SizeReport(k16SharedRuntimeMapArtifacts)
            println("map_section name=development_only")
            runK16SizeReport(k16DevelopmentOnlyMapArtifacts)
        }
    }

tasks.named("processResources") {
    dependsOn(linkK16BiosFlash)
    dependsOn(putK16SystemStorage0Init)
}

tasks.named("processTestResources") {
    dependsOn(putK16DevelopmentStorage0TestPrograms)
}

tasks.named<Test>("test") {
    dependsOn(compileK16UserFaultTest)
    dependsOn(compileK16SyscallFaultTest)
}

tasks.register<Test>("profileK16RuntimeWait") {
    description = "Runs the bundled K16 runtime wait profiling workload and prints runtime metrics."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16RuntimeWaitProfilingTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
}
