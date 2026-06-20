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
val generatedK16StatTarget = generatedK16GuestTarget.map { it.dir("stat") }
val generatedK16WriteTarget = generatedK16GuestTarget.map { it.dir("write") }
val generatedK16RmTarget = generatedK16GuestTarget.map { it.dir("rm") }
val generatedK16MkdirTarget = generatedK16GuestTarget.map { it.dir("mkdir") }
val generatedK16RmdirTarget = generatedK16GuestTarget.map { it.dir("rmdir") }
val generatedK16AllocTestTarget = generatedK16GuestTarget.map { it.dir("alloc-test") }
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
val k16MotdSource = rootProject.layout.projectDirectory.file("rust/guest/k16-cat/motd.txt")
val k16AllocTestManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-alloc-test/Cargo.toml")
val k16AllocTestSource = rootProject.layout.projectDirectory.file("rust/guest/k16-alloc-test/src/main.rs")
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
val k16StatArtifact = generatedK16FirmwareArtifacts.map { it.file("stat.kx") }
val k16WriteArtifact = generatedK16FirmwareArtifacts.map { it.file("write.kx") }
val k16RmArtifact = generatedK16FirmwareArtifacts.map { it.file("rm.kx") }
val k16MkdirArtifact = generatedK16FirmwareArtifacts.map { it.file("mkdir.kx") }
val k16RmdirArtifact = generatedK16FirmwareArtifacts.map { it.file("rmdir.kx") }
val k16AllocTestArtifact = generatedK16FirmwareArtifacts.map { it.file("alloc-test.kx") }
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }

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
    buildStd: String = "core",
) {
    val toolchain = resolveK16Toolchain()
    val profile = k16FirmwareProfileName()
    val cpuHelpers = targetDir.resolve("k16-cpu-helpers.o")
    val startup = targetDir.resolve("k16-startup.o")
    output.parentFile.mkdirs()
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
    buildRuntimeObject("k16-cpu-helpers", cpuHelpers)
    val needsStartup = k16Target == "program" || k16Target == "program-dynamic"
    if (needsStartup) {
        buildRuntimeObject("k16-startup", startup, target = k16Target)
    }
    val runtimeLinkArgs =
        buildList {
            if (needsStartup) {
                add("-C link-arg=${startup.absolutePath}")
            }
            add("-C link-arg=${cpuHelpers.absolutePath}")
        }.joinToString(" ")
    val command =
        listOf(toolchain.cargo.absolutePath, "rustc") +
            k16CargoProfileArgs(profile) +
            listOf(
                "-Zbuild-std=$buildStd",
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
        "-C linker=${toolchain.linker.absolutePath} $runtimeLinkArgs -C link-arg=--k16-target=$k16Target -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no"
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16BootManifest.asFile,
                targetDir = generatedK16BootTarget.get().asFile,
                binName = "k16-boot",
                k16Target = "boot",
                output = k16BootArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16KernelManifest.asFile,
                targetDir = generatedK16KernelTarget.get().asFile,
                binName = "k16-kernel",
                k16Target = "kernel",
                output = k16KernelArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16InitManifest.asFile,
                targetDir = generatedK16GuestTarget.get().dir("init").asFile,
                binName = "k16-init",
                k16Target = "program-dynamic",
                output = k16InitArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16ShellManifest.asFile,
                targetDir = generatedK16ShellTarget.get().asFile,
                binName = "k16-shell",
                k16Target = "program-dynamic",
                output = k16ShellArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16UnameManifest.asFile,
                targetDir = generatedK16UnameTarget.get().asFile,
                binName = "k16-uname",
                k16Target = "program-dynamic",
                output = k16UnameArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16LsManifest.asFile,
                targetDir = generatedK16LsTarget.get().asFile,
                binName = "k16-ls",
                k16Target = "program-dynamic",
                output = k16LsArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16CatManifest.asFile,
                targetDir = generatedK16CatTarget.get().asFile,
                binName = "k16-cat",
                k16Target = "program-dynamic",
                output = k16CatArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16CpManifest.asFile,
                targetDir = generatedK16CpTarget.get().asFile,
                binName = "k16-cp",
                k16Target = "program-dynamic",
                output = k16CpArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16StatManifest.asFile,
                targetDir = generatedK16StatTarget.get().asFile,
                binName = "k16-stat",
                k16Target = "program-dynamic",
                output = k16StatArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16WriteManifest.asFile,
                targetDir = generatedK16WriteTarget.get().asFile,
                binName = "k16-write",
                k16Target = "program-dynamic",
                output = k16WriteArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16RmManifest.asFile,
                targetDir = generatedK16RmTarget.get().asFile,
                binName = "k16-rm",
                k16Target = "program-dynamic",
                output = k16RmArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16MkdirManifest.asFile,
                targetDir = generatedK16MkdirTarget.get().asFile,
                binName = "k16-mkdir",
                k16Target = "program-dynamic",
                output = k16MkdirArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16RmdirManifest.asFile,
                targetDir = generatedK16RmdirTarget.get().asFile,
                binName = "k16-rmdir",
                k16Target = "program-dynamic",
                output = k16RmdirArtifact.get().asFile,
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
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))

        doLast {
            project.compileK16GuestRustBin(
                manifest = k16AllocTestManifest.asFile,
                targetDir = generatedK16AllocTestTarget.get().asFile,
                binName = "k16-alloc-test",
                k16Target = "program-dynamic",
                output = k16AllocTestArtifact.get().asFile,
                buildStd = "core,alloc",
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
        description = "Writes the bundled K16 user programs into ROOT K16FS /bin."
        group = "k16"
        dependsOn(compileK16SystemStorage0, compileK16SystemInit, compileK16SystemShell, compileK16SystemUname, compileK16SystemLs, compileK16SystemCat, compileK16SystemCp, compileK16SystemStat, compileK16SystemWrite, compileK16SystemRm, compileK16SystemMkdir, compileK16SystemRmdir, compileK16SystemAllocTest)
        dependsOn(rootProject.tasks.named("prepareK16Toolchain"))
        inputs.file(k16ToolchainConfig)
        inputs.file(k16InitArtifact)
        inputs.file(k16ShellArtifact)
        inputs.file(k16UnameArtifact)
        inputs.file(k16LsArtifact)
        inputs.file(k16CatArtifact)
        inputs.file(k16CpArtifact)
        inputs.file(k16StatArtifact)
        inputs.file(k16WriteArtifact)
        inputs.file(k16RmArtifact)
        inputs.file(k16MkdirArtifact)
        inputs.file(k16RmdirArtifact)
        inputs.file(k16AllocTestArtifact)
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
                "/bin/alloc-test.kx",
                k16AllocTestArtifact.get().asFile.absolutePath,
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

tasks.named("processResources") {
    dependsOn(linkK16BiosFlash)
    dependsOn(putK16SystemStorage0Init)
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
