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
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareTestResources = layout.buildDirectory.dir("generated/k16-firmware-test-resources")
val generatedK16FirmwareArtifacts = layout.buildDirectory.dir("generated/k16-firmware-artifacts")
val generatedK16GuestTarget = layout.buildDirectory.dir("generated/k16-guest-target")
val generatedK16BiosTarget = generatedK16GuestTarget.map { it.dir("bios") }
val generatedK16BootTarget = generatedK16GuestTarget.map { it.dir("boot") }
val generatedK16KernelTarget = generatedK16GuestTarget.map { it.dir("kernel") }
val generatedK16ShellTarget = generatedK16GuestTarget.map { it.dir("shell") }
val generatedK16UnameTarget = generatedK16GuestTarget.map { it.dir("uname") }
val generatedK16LsTarget = generatedK16GuestTarget.map { it.dir("ls") }
val generatedK16CatTarget = generatedK16GuestTarget.map { it.dir("cat") }
val generatedK16CpTarget = generatedK16GuestTarget.map { it.dir("cp") }
val generatedK16MvTarget = generatedK16GuestTarget.map { it.dir("mv") }
val generatedK16StatTarget = generatedK16GuestTarget.map { it.dir("stat") }
val generatedK16WriteTarget = generatedK16GuestTarget.map { it.dir("write") }
val generatedK16RmTarget = generatedK16GuestTarget.map { it.dir("rm") }
val generatedK16MkdirTarget = generatedK16GuestTarget.map { it.dir("mkdir") }
val generatedK16RmdirTarget = generatedK16GuestTarget.map { it.dir("rmdir") }
val generatedK16SharedSmokeRuntimeTarget = generatedK16GuestTarget.map { it.dir("shared-smoke-runtime") }
val generatedK16SharedRuntimeTestTarget = generatedK16GuestTarget.map { it.dir("shared-runtime-test") }
val generatedK16HostedHelloTarget = generatedK16GuestTarget.map { it.dir("hosted-hello") }
val generatedK16HostedCatTarget = generatedK16GuestTarget.map { it.dir("hosted-cat") }
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
val k16UnameManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-uname/Cargo.toml")
val k16UnameSource = rootProject.layout.projectDirectory.file("rust/guest/k16-uname/src/main.rs")
val k16LsManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-ls/Cargo.toml")
val k16LsSource = rootProject.layout.projectDirectory.file("rust/guest/k16-ls/src/main.rs")
val k16CatManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-cat/Cargo.toml")
val k16CatSource = rootProject.layout.projectDirectory.file("rust/guest/k16-cat/src/main.rs")
val k16CpManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-cp/Cargo.toml")
val k16CpSource = rootProject.layout.projectDirectory.file("rust/guest/k16-cp/src/main.rs")
val k16MvManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-mv/Cargo.toml")
val k16MvSource = rootProject.layout.projectDirectory.file("rust/guest/k16-mv/src/main.rs")
val k16StatManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-stat/Cargo.toml")
val k16StatSource = rootProject.layout.projectDirectory.file("rust/guest/k16-stat/src/main.rs")
val k16WriteManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-write/Cargo.toml")
val k16WriteSource = rootProject.layout.projectDirectory.file("rust/guest/k16-write/src/main.rs")
val k16RmManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-rm/Cargo.toml")
val k16RmSource = rootProject.layout.projectDirectory.file("rust/guest/k16-rm/src/main.rs")
val k16MkdirManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-mkdir/Cargo.toml")
val k16MkdirSource = rootProject.layout.projectDirectory.file("rust/guest/k16-mkdir/src/main.rs")
val k16RmdirManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-rmdir/Cargo.toml")
val k16RmdirSource = rootProject.layout.projectDirectory.file("rust/guest/k16-rmdir/src/main.rs")
val k16SharedSmokeRuntimeManifest =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-smoke-runtime/Cargo.toml")
val k16SharedSmokeRuntimeSource =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-smoke-runtime/src/main.rs")
val k16SharedRuntimeTestManifest =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-runtime-test/Cargo.toml")
val k16SharedRuntimeTestSource =
    rootProject.layout.projectDirectory.file("rust/guest/k16-shared-runtime-test/src/main.rs")
val k16HostedHelloManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-hosted-hello/Cargo.toml")
val k16HostedHelloSource = rootProject.layout.projectDirectory.file("rust/guest/k16-hosted-hello/src/main.rs")
val k16HostedCatManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-hosted-cat/Cargo.toml")
val k16HostedCatSource = rootProject.layout.projectDirectory.file("rust/guest/k16-hosted-cat/src/main.rs")
val k16MotdSource = rootProject.layout.projectDirectory.file("rust/guest/k16-cat/motd.txt")
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
val k16SharedSmokeRuntimeArtifact = generatedK16FirmwareArtifacts.map { it.file("k16-shared-smoke.k16so") }
val k16SharedRuntimeTestArtifact = generatedK16FirmwareArtifacts.map { it.file("shared-runtime-test.kx") }
val k16HostedHelloArtifact = generatedK16FirmwareArtifacts.map { it.file("hosted-hello.kx") }
val k16HostedCatArtifact = generatedK16FirmwareArtifacts.map { it.file("hosted-cat.kx") }
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
val k16SharedSmokeRuntimeMapArtifact =
    k16SharedSmokeRuntimeArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16SharedRuntimeTestMapArtifact =
    k16SharedRuntimeTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16HostedHelloMapArtifact =
    k16HostedHelloArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16HostedCatMapArtifact =
    k16HostedCatArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16AllocTestMapArtifact =
    k16AllocTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16ProcTestMapArtifact = k16ProcTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16SyscallFaultTestMapArtifact =
    k16SyscallFaultTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16UserFaultTestMapArtifact =
    k16UserFaultTestArtifact.map { it.asFile.resolveSibling("${it.asFile.nameWithoutExtension}.map") }
val k16UserlandMapArtifacts =
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
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }
val k16DevelopmentStorage0Resource =
    generatedK16FirmwareTestResources.map { it.file("firmware/k16-system-storage0-dev.kv") }

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
    val needsCpuHelpers = k16Target != "shared-object"
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
        description = "Compiles and links the bundled Rust K16 uname utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16UnameManifest)
        inputs.file(k16UnameSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16UnameArtifact)
        outputs.file(k16UnameMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16UnameManifest.asFile,
                targetDir = generatedK16UnameTarget.get().asFile,
                binName = "k16-uname",
                k16Target = "program-dynamic",
                output = k16UnameArtifact.get().asFile,
                mapOutput = k16UnameMapArtifact.get(),
            )
        }
    }

val compileK16SystemLs =
    tasks.register("compileK16SystemLs") {
        description = "Compiles and links the bundled Rust K16 ls utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16LsManifest)
        inputs.file(k16LsSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16LsArtifact)
        outputs.file(k16LsMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16LsManifest.asFile,
                targetDir = generatedK16LsTarget.get().asFile,
                binName = "k16-ls",
                k16Target = "program-dynamic",
                output = k16LsArtifact.get().asFile,
                mapOutput = k16LsMapArtifact.get(),
            )
        }
    }

val compileK16SystemCat =
    tasks.register("compileK16SystemCat") {
        description = "Compiles and links the bundled Rust K16 cat utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16CatManifest)
        inputs.file(k16CatSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16CatArtifact)
        outputs.file(k16CatMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16CatManifest.asFile,
                targetDir = generatedK16CatTarget.get().asFile,
                binName = "k16-cat",
                k16Target = "program-dynamic",
                output = k16CatArtifact.get().asFile,
                mapOutput = k16CatMapArtifact.get(),
            )
        }
    }

val compileK16SystemCp =
    tasks.register("compileK16SystemCp") {
        description = "Compiles and links the bundled Rust K16 cp utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16CpManifest)
        inputs.file(k16CpSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16CpArtifact)
        outputs.file(k16CpMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16CpManifest.asFile,
                targetDir = generatedK16CpTarget.get().asFile,
                binName = "k16-cp",
                k16Target = "program-dynamic",
                output = k16CpArtifact.get().asFile,
                mapOutput = k16CpMapArtifact.get(),
            )
        }
    }

val compileK16SystemMv =
    tasks.register("compileK16SystemMv") {
        description = "Compiles and links the bundled Rust K16 mv utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16MvManifest)
        inputs.file(k16MvSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16MvArtifact)
        outputs.file(k16MvMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16MvManifest.asFile,
                targetDir = generatedK16MvTarget.get().asFile,
                binName = "k16-mv",
                k16Target = "program-dynamic",
                output = k16MvArtifact.get().asFile,
                mapOutput = k16MvMapArtifact.get(),
            )
        }
    }

val compileK16SystemStat =
    tasks.register("compileK16SystemStat") {
        description = "Compiles and links the bundled Rust K16 stat utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16StatManifest)
        inputs.file(k16StatSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16StatArtifact)
        outputs.file(k16StatMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16StatManifest.asFile,
                targetDir = generatedK16StatTarget.get().asFile,
                binName = "k16-stat",
                k16Target = "program-dynamic",
                output = k16StatArtifact.get().asFile,
                mapOutput = k16StatMapArtifact.get(),
            )
        }
    }

val compileK16SystemWrite =
    tasks.register("compileK16SystemWrite") {
        description = "Compiles and links the bundled Rust K16 write utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16WriteManifest)
        inputs.file(k16WriteSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16WriteArtifact)
        outputs.file(k16WriteMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16WriteManifest.asFile,
                targetDir = generatedK16WriteTarget.get().asFile,
                binName = "k16-write",
                k16Target = "program-dynamic",
                output = k16WriteArtifact.get().asFile,
                mapOutput = k16WriteMapArtifact.get(),
            )
        }
    }

val compileK16SystemRm =
    tasks.register("compileK16SystemRm") {
        description = "Compiles and links the bundled Rust K16 rm utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16RmManifest)
        inputs.file(k16RmSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16RmArtifact)
        outputs.file(k16RmMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16RmManifest.asFile,
                targetDir = generatedK16RmTarget.get().asFile,
                binName = "k16-rm",
                k16Target = "program-dynamic",
                output = k16RmArtifact.get().asFile,
                mapOutput = k16RmMapArtifact.get(),
            )
        }
    }

val compileK16SystemMkdir =
    tasks.register("compileK16SystemMkdir") {
        description = "Compiles and links the bundled Rust K16 mkdir utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16MkdirManifest)
        inputs.file(k16MkdirSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16MkdirArtifact)
        outputs.file(k16MkdirMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16MkdirManifest.asFile,
                targetDir = generatedK16MkdirTarget.get().asFile,
                binName = "k16-mkdir",
                k16Target = "program-dynamic",
                output = k16MkdirArtifact.get().asFile,
                mapOutput = k16MkdirMapArtifact.get(),
            )
        }
    }

val compileK16SystemRmdir =
    tasks.register("compileK16SystemRmdir") {
        description = "Compiles and links the bundled Rust K16 rmdir utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16RmdirManifest)
        inputs.file(k16RmdirSource)
        inputsK16RuntimeCrates()
        inputsKraftStdCrate()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16RmdirArtifact)
        outputs.file(k16RmdirMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16RmdirManifest.asFile,
                targetDir = generatedK16RmdirTarget.get().asFile,
                binName = "k16-rmdir",
                k16Target = "program-dynamic",
                output = k16RmdirArtifact.get().asFile,
                mapOutput = k16RmdirMapArtifact.get(),
            )
        }
    }

val compileK16SharedSmokeRuntime =
    tasks.register("compileK16SharedSmokeRuntime") {
        description = "Compiles and links the bundled K16 shared runtime smoke object into a K16E shared object."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16SharedSmokeRuntimeManifest)
        inputs.file(k16SharedSmokeRuntimeSource)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16SharedSmokeRuntimeArtifact)
        outputs.file(k16SharedSmokeRuntimeMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16SharedSmokeRuntimeManifest.asFile,
                targetDir = generatedK16SharedSmokeRuntimeTarget.get().asFile,
                binName = "k16-shared-smoke-runtime",
                k16Target = "shared-object",
                output = k16SharedSmokeRuntimeArtifact.get().asFile,
                mapOutput = k16SharedSmokeRuntimeMapArtifact.get(),
            )
        }
    }

val compileK16SharedRuntimeTest =
    tasks.register("compileK16SharedRuntimeTest") {
        description = "Compiles and links the bundled K16 shared runtime import smoke into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16SharedRuntimeTestManifest)
        inputs.file(k16SharedRuntimeTestSource)
        inputsK16RuntimeCrates()
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16SharedRuntimeTestArtifact)
        outputs.file(k16SharedRuntimeTestMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16SharedRuntimeTestManifest.asFile,
                targetDir = generatedK16SharedRuntimeTestTarget.get().asFile,
                binName = "k16-shared-runtime-test",
                k16Target = "program-dynamic",
                output = k16SharedRuntimeTestArtifact.get().asFile,
                mapOutput = k16SharedRuntimeTestMapArtifact.get(),
                extraRuntimeLinkArgs =
                    listOf(
                        "-C link-arg=--k16-import",
                        "-C link-arg=k16-shared-smoke.k16so:k16_shared_memcmp",
                    ),
            )
        }
    }

val reportK16UserlandSize =
    tasks.register("reportK16UserlandSize") {
        description = "Reports duplicated retained-section size across bundled K16 userland maps."
        group = "k16"
        inputs.files(k16UserlandMapArtifacts)
        dependsOn(compileK16SystemInit, compileK16SystemShell, compileK16SystemUname, compileK16SystemLs, compileK16SystemCat, compileK16SystemCp, compileK16SystemMv, compileK16SystemStat, compileK16SystemWrite, compileK16SystemRm, compileK16SystemMkdir, compileK16SystemRmdir)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            val toolchain = resolveK16Toolchain()
            val args = mutableListOf(toolchain.cli.absolutePath)
            args.add("size-report")
            k16UserlandMapArtifacts.forEach { mapArtifact ->
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

val compileK16HostedHello =
    tasks.register("compileK16HostedHello") {
        description = "Compiles and links the K16 hosted std hello proof program into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16HostedHelloManifest)
        inputs.file(k16HostedHelloSource)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16HostedHelloArtifact)
        outputs.file(k16HostedHelloMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16HostedHelloManifest.asFile,
                targetDir = generatedK16HostedHelloTarget.get().asFile,
                binName = "k16-hosted-hello",
                k16Target = "program-dynamic",
                output = k16HostedHelloArtifact.get().asFile,
                mapOutput = k16HostedHelloMapArtifact.get(),
                buildStd = "std,panic_abort",
                buildStdFeatures = "compiler-builtins-mem",
            )
        }
    }

val compileK16HostedCat =
    tasks.register("compileK16HostedCat") {
        description = "Compiles and links the K16 hosted std cat proof utility into a dynamic K16E program artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16HostedCatManifest)
        inputs.file(k16HostedCatSource)
        inputs.file(k16HostToolsManifest)
        inputs.dir(k16HostToolsSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.property("k16FirmwareProfile", k16FirmwareProfile)
        outputs.file(k16HostedCatArtifact)
        outputs.file(k16HostedCatMapArtifact)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16HostedCatManifest.asFile,
                targetDir = generatedK16HostedCatTarget.get().asFile,
                binName = "k16-hosted-cat",
                k16Target = "program-dynamic",
                output = k16HostedCatArtifact.get().asFile,
                mapOutput = k16HostedCatMapArtifact.get(),
                buildStd = "std,panic_abort",
                buildStdFeatures = "compiler-builtins-mem",
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
        dependsOn(compileK16SystemStorage0, compileK16SystemInit, compileK16SystemShell, compileK16SystemUname, compileK16SystemLs, compileK16SystemCat, compileK16SystemCp, compileK16SystemMv, compileK16SystemStat, compileK16SystemWrite, compileK16SystemRm, compileK16SystemMkdir, compileK16SystemRmdir, compileK16SharedSmokeRuntime, compileK16HostedCat)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16InitArtifact)
        inputs.file(k16ShellArtifact)
        inputs.file(k16UnameArtifact)
        inputs.file(k16LsArtifact)
        inputs.file(k16CatArtifact)
        inputs.file(k16CpArtifact)
        inputs.file(k16MvArtifact)
        inputs.file(k16StatArtifact)
        inputs.file(k16WriteArtifact)
        inputs.file(k16RmArtifact)
        inputs.file(k16MkdirArtifact)
        inputs.file(k16RmdirArtifact)
        inputs.file(k16SharedSmokeRuntimeArtifact)
        inputs.file(k16HostedCatArtifact)
        inputs.file(k16MotdSource)
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
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/init.kx",
                k16InitArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/shell.kx",
                k16ShellArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/uname.kx",
                k16UnameArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/ls.kx",
                k16LsArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/cat.kx",
                k16CatArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/cp.kx",
                k16CpArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/mv.kx",
                k16MvArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/stat.kx",
                k16StatArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/write.kx",
                k16WriteArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/rm.kx",
                k16RmArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/mkdir.kx",
                k16MkdirArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/rmdir.kx",
                k16RmdirArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/lib/k16-shared-smoke.k16so",
                k16SharedSmokeRuntimeArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/hosted-cat.kx",
                k16HostedCatArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/etc/motd",
                k16MotdSource.asFile.absolutePath,
            )
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
        dependsOn(putK16SystemStorage0Init, compileK16SystemAllocTest, compileK16SystemProcTest, compileK16SharedRuntimeTest)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16SystemStorage0Resource)
        inputs.file(k16AllocTestArtifact)
        inputs.file(k16ProcTestArtifact)
        inputs.file(k16SharedRuntimeTestArtifact)
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

            devStorage.parentFile.mkdirs()
            k16SystemStorage0Resource.get().asFile.copyTo(devStorage, overwrite = true)
            runK16Command(
                "volume",
                "extract-partition",
                devStorage.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/alloc-test.kx",
                k16AllocTestArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/shared-runtime-test.kx",
                k16SharedRuntimeTestArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "fs",
                "kfs",
                "put",
                rootPartition.absolutePath,
                "/bin/proc-test.kx",
                k16ProcTestArtifact.get().asFile.absolutePath,
            )
            runK16Command(
                "volume",
                "replace-partition",
                devStorage.absolutePath,
                "ROOT",
                rootPartition.absolutePath,
            )
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
