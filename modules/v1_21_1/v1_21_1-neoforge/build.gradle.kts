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

val rustVmNativePlatform = currentRustVmNativePlatform()
val rustVmNativeLibrary = rootProject.layout.projectDirectory.file("native/rux-vm/target/debug/${rustVmNativePlatform.libraryName}")
val generatedRuxFirmwareResources = layout.buildDirectory.dir("generated/rux-firmware-resources")
val ruxCompilerManifest = rootProject.layout.projectDirectory.file("native/rux-compiler/Cargo.toml")
val rux16BiosSource = rootProject.layout.projectDirectory.file("native/rux-compiler/examples/firmware/rux16_bios.rx")
val rux16BiosFlashResource = generatedRuxFirmwareResources.map { it.file("firmware/rux16-bios.flash") }

val compileRux16BiosFlash =
    tasks.register<Exec>("compileRux16BiosFlash") {
        description = "Compiles the bundled Rux16 BIOS source into a raw BIOS flash resource."
        group = "rux"
        inputs.file(ruxCompilerManifest)
        inputs.file(rux16BiosSource)
        outputs.file(rux16BiosFlashResource)

        doFirst {
            rux16BiosFlashResource.get().asFile.parentFile.mkdirs()
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
            rux16BiosSource.asFile.absolutePath,
            "-o",
            rux16BiosFlashResource.get().asFile.absolutePath,
        )
    }

sourceSets.main {
    resources.srcDir(generatedRuxFirmwareResources)
}

tasks.named("processResources") {
    dependsOn(compileRux16BiosFlash)
}
