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
            sourceSet("main", project(projects.v1211Common.path))
            sourceSet("main", project(projects.core.path))
            sourceSet("main", project(":native-runtime"))
            sourceSet(gameTest.name)
        }
    }
}

dependencies {
    common(project(path = projects.v1211Common.path, configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = projects.v1211Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    modImplementation(libs.geckolib.neoforge.v1211)

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v1211Common.path, configuration = "namedElements"))
}

val k16VmNativePlatform = currentK16VmNativePlatform()
val k16VmNativeLibrary = rootProject.layout.projectDirectory.file("rust/host/k16-vm/target/debug/${k16VmNativePlatform.libraryName}")
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareArtifacts = layout.buildDirectory.dir("generated/k16-firmware-artifacts")
val generatedK16GuestTarget = layout.buildDirectory.dir("generated/k16-guest-target")
val generatedK16BiosTarget = generatedK16GuestTarget.map { it.dir("bios") }
val generatedK16BootTarget = generatedK16GuestTarget.map { it.dir("boot") }
val generatedK16KernelTarget = generatedK16GuestTarget.map { it.dir("kernel") }
val k16ToolsManifest = rootProject.layout.projectDirectory.file("rust/host/k16-tools/Cargo.toml")
val k16ToolsSource = rootProject.layout.projectDirectory.dir("rust/host/k16-tools/src")
val k16GuestManifest = rootProject.layout.projectDirectory.file("rust/guest/Cargo.toml")
val k16BiosManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/Cargo.toml")
val k16BiosSource = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/src/main.rs")
val k16BootManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/Cargo.toml")
val k16BootSource = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/src/main.rs")
val k16KernelManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-kernel/Cargo.toml")
val k16KernelSource = rootProject.layout.projectDirectory.file("rust/guest/k16-kernel/src/main.rs")
val k16RustTargetSpec = rootProject.layout.projectDirectory.file("tools/k16-unknown-kraftos.json")
val k16BiosFlashResource = generatedK16FirmwareResources.map { it.file("firmware/k16-bios.kflash") }
val k16BootArtifact = generatedK16FirmwareArtifacts.map { it.file("kernel-loader.kb") }
val k16KernelArtifact = generatedK16FirmwareArtifacts.map { it.file("display-ok.kx") }
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }

fun deleteK16RustBinOutputs(
    targetDir: File,
    binName: String,
) {
    val debugDir = targetDir.resolve("k16-unknown-kraftos/debug")
    debugDir.resolve(binName).delete()
    debugDir.resolve("$binName.d").delete()
    val depsDir = targetDir.resolve("k16-unknown-kraftos/debug/deps")
    depsDir
        .listFiles()
        ?.filter { it.name.startsWith("$binName-") }
        ?.forEach { file ->
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
}

fun copyK16RustBinOutput(
    targetDir: File,
    binName: String,
    output: File,
) {
    val artifact = targetDir.resolve("k16-unknown-kraftos/debug/$binName")
    check(artifact.isFile) {
        "Expected linked K16 Rust bin artifact for $binName at $artifact"
    }
    output.parentFile.mkdirs()
    artifact.copyTo(output, overwrite = true)
}

val linkK16BiosFlash =
    tasks.register<Exec>("linkK16BiosFlash") {
        description = "Compiles and links the bundled Rust K16 BIOS bin crate into a raw BIOS flash resource."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BiosManifest)
        inputs.file(k16BiosSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16BiosFlashResource)

        doFirst {
            val cargo =
                providers.environmentVariable("K16_CARGO").orNull
                    ?: throw GradleException("K16_CARGO must point to a nightly-capable cargo to build rust/guest/k16-bios")
            val rustc =
                providers.environmentVariable("K16_RUSTC").orNull
                    ?: throw GradleException("K16_RUSTC must point to a custom K16 rustc to build rust/guest/k16-bios")
            val linker =
                providers.environmentVariable("K16_LD").orNull
                    ?: throw GradleException("K16_LD must point to the k16-ld linker driver to build rust/guest/k16-bios")
            k16BiosFlashResource.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16BiosTarget.get().asFile, "k16-bios")
            environment("RUSTC", rustc)
            environment(
                "RUSTFLAGS",
                "-C linker=$linker -C link-arg=--k16-target=bios -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                cargo,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16BiosManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-bios",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16BiosTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16BiosTarget.get().asFile,
                binName = "k16-bios",
                output = k16BiosFlashResource.get().asFile,
            )
        }
    }

val compileK16SystemBoot =
    tasks.register<Exec>("compileK16SystemBoot") {
        description = "Compiles and links the bundled Rust K16 bootloader bin crate into a K16E boot artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BootManifest)
        inputs.file(k16BootSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16BootArtifact)

        doFirst {
            val cargo =
                providers.environmentVariable("K16_CARGO").orNull
                    ?: throw GradleException("K16_CARGO must point to a custom K16 cargo to build rust/guest/k16-boot")
            val rustc =
                providers.environmentVariable("K16_RUSTC").orNull
                    ?: throw GradleException("K16_RUSTC must point to a custom K16 rustc to build rust/guest/k16-boot")
            val linker =
                providers.environmentVariable("K16_LD").orNull
                    ?: throw GradleException("K16_LD must point to the k16-ld linker driver to build rust/guest/k16-boot")
            k16BootArtifact.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16BootTarget.get().asFile, "k16-boot")
            environment("RUSTC", rustc)
            environment(
                "RUSTFLAGS",
                "-C linker=$linker -C link-arg=--k16-target=boot -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                cargo,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16BootManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-boot",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16BootTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16BootTarget.get().asFile,
                binName = "k16-boot",
                output = k16BootArtifact.get().asFile,
            )
        }
    }

val compileK16SystemKernel =
    tasks.register<Exec>("compileK16SystemKernel") {
        description = "Compiles and links the bundled Rust K16 kernel bin crate into a K16E kernel artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16KernelManifest)
        inputs.file(k16KernelSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16KernelArtifact)

        doFirst {
            val cargo =
                providers.environmentVariable("K16_CARGO").orNull
                    ?: throw GradleException("K16_CARGO must point to a custom K16 cargo to build rust/guest/k16-kernel")
            val rustc =
                providers.environmentVariable("K16_RUSTC").orNull
                    ?: throw GradleException("K16_RUSTC must point to a custom K16 rustc to build rust/guest/k16-kernel")
            val linker =
                providers.environmentVariable("K16_LD").orNull
                    ?: throw GradleException("K16_LD must point to the k16-ld linker driver to build rust/guest/k16-kernel")
            k16KernelArtifact.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16KernelTarget.get().asFile, "k16-kernel")
            environment("RUSTC", rustc)
            environment(
                "RUSTFLAGS",
                "-C linker=$linker -C link-arg=--k16-target=kernel -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                cargo,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16KernelManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-kernel",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16KernelTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16KernelTarget.get().asFile,
                binName = "k16-kernel",
                output = k16KernelArtifact.get().asFile,
            )
        }
    }

val createK16SystemStorage0 =
    tasks.register<Exec>("createK16SystemStorage0") {
        description = "Creates the bundled K16 system storage0 volume resource."
        group = "k16"
        inputs.file(k16ToolsManifest)

        doFirst {
            k16SystemStorage0Resource.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "init",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            "--size",
            "1048576",
        )
    }

val putK16SystemStorage0Boot =
    tasks.register<Exec>("putK16SystemStorage0Boot") {
        description = "Writes the bundled K16 bootloader into the system storage0 volume resource."
        group = "k16"
        dependsOn(createK16SystemStorage0, compileK16SystemBoot)
        inputs.file(k16ToolsManifest)
        inputs.file(k16BootArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "put-boot",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            k16BootArtifact.get().asFile.absolutePath,
        )
    }

val compileK16SystemStorage0 =
    tasks.register<Exec>("compileK16SystemStorage0") {
        description = "Writes the bundled K16 kernel into the system storage0 volume resource."
        group = "k16"
        dependsOn(putK16SystemStorage0Boot, compileK16SystemKernel)
        inputs.file(k16ToolsManifest)
        inputs.file(k16BootArtifact)
        inputs.file(k16KernelArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "put-kernel",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            k16KernelArtifact.get().asFile.absolutePath,
        )
    }

sourceSets.main {
    resources.srcDir(generatedK16FirmwareResources)
}

tasks.named("processResources") {
    dependsOn(linkK16BiosFlash)
    dependsOn(compileK16SystemStorage0)
}
