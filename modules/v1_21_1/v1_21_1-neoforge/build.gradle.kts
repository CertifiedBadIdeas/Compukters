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
val k16VmNativeLibrary = rootProject.layout.projectDirectory.file("native/k16-vm/target/debug/${k16VmNativePlatform.libraryName}")
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareArtifacts = layout.buildDirectory.dir("generated/k16-firmware-artifacts")
val ruxCompilerManifest = rootProject.layout.projectDirectory.file("native/rux-compiler/Cargo.toml")
val k16BiosSource = rootProject.layout.projectDirectory.file("native/rux-compiler/examples/firmware/rux16_bios.rx")
val k16BootSource = rootProject.layout.projectDirectory.file("native/rux-compiler/examples/boot/kernel_loader.rx")
val k16KernelSource = rootProject.layout.projectDirectory.file("native/rux-compiler/examples/kernel/display_ok.rx")
val k16BiosFlashResource = generatedK16FirmwareResources.map { it.file("firmware/k16-bios.kflash") }
val k16BootArtifact = generatedK16FirmwareArtifacts.map { it.file("kernel-loader.kb") }
val k16KernelArtifact = generatedK16FirmwareArtifacts.map { it.file("display-ok.kx") }
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }

val compileK16BiosFlash =
    tasks.register<Exec>("compileK16BiosFlash") {
        description = "Compiles the bundled K16 BIOS source into a raw BIOS flash resource."
        group = "k16"
        inputs.file(ruxCompilerManifest)
        inputs.file(k16BiosSource)
        outputs.file(k16BiosFlashResource)

        doFirst {
            k16BiosFlashResource.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
            "--bin",
            "rux",
            "--",
            "compile",
            "--target",
            "bios",
            k16BiosSource.asFile.absolutePath,
            "-o",
            k16BiosFlashResource.get().asFile.absolutePath,
        )
    }

val compileK16SystemBoot =
    tasks.register<Exec>("compileK16SystemBoot") {
        description = "Compiles the bundled K16 bootloader artifact."
        group = "k16"
        inputs.file(ruxCompilerManifest)
        inputs.file(k16BootSource)
        outputs.file(k16BootArtifact)

        doFirst {
            k16BootArtifact.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
            "--bin",
            "rux",
            "--",
            "compile",
            "--target",
            "boot",
            k16BootSource.asFile.absolutePath,
            "-o",
            k16BootArtifact.get().asFile.absolutePath,
        )
    }

val compileK16SystemKernel =
    tasks.register<Exec>("compileK16SystemKernel") {
        description = "Compiles the bundled K16 kernel artifact."
        group = "k16"
        inputs.file(ruxCompilerManifest)
        inputs.file(k16KernelSource)
        outputs.file(k16KernelArtifact)

        doFirst {
            k16KernelArtifact.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
            "--bin",
            "rux",
            "--",
            "compile",
            "--target",
            "kernel",
            k16KernelSource.asFile.absolutePath,
            "-o",
            k16KernelArtifact.get().asFile.absolutePath,
        )
    }

val createK16SystemStorage0 =
    tasks.register<Exec>("createK16SystemStorage0") {
        description = "Creates the bundled K16 system storage0 volume resource."
        group = "k16"
        inputs.file(ruxCompilerManifest)

        doFirst {
            k16SystemStorage0Resource.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
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
        inputs.file(ruxCompilerManifest)
        inputs.file(k16BootArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
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
        inputs.file(ruxCompilerManifest)
        inputs.file(k16BootArtifact)
        inputs.file(k16KernelArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            ruxCompilerManifest.asFile.absolutePath,
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
    dependsOn(compileK16BiosFlash)
    dependsOn(compileK16SystemStorage0)
}
