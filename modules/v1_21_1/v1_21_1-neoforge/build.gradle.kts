/*
 * The Compukters Developers
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

import net.fabricmc.loom.task.RemapJarTask
import java.util.Locale
import java.util.zip.ZipFile

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
            property("neoforge.enabledGameTestNamespaces", "compukters,minecraft")
            property("neoforge.gameTestServer", "true")
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

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v1211Common.path, configuration = "namedElements"))
}

val terminalFixture = rootProject.file("host/compukter-vm/tests/fixtures/terminal-session.hex")

tasks.test {
    inputs.file(terminalFixture)
    doFirst {
        systemProperty("compukter.vm.terminalFixture", terminalFixture.absolutePath)
    }
}

val nativeOs =
    when {
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        else -> error("unsupported native build operating system: ${System.getProperty("os.name")}")
    }
val nativeArch =
    when (System.getProperty("os.arch").trim().lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("unsupported native build architecture: ${System.getProperty("os.arch")}")
    }
val nativeFilename =
    when (nativeOs) {
        "linux" -> "libcompukter_jni.so"
        "windows" -> "compukter_jni.dll"
        "macos" -> "libcompukter_jni.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val productionJar = tasks.named<RemapJarTask>("remapJar")
val verifyPackagedCompukterJni =
    tasks.register("verifyPackagedCompukterJni") {
        description = "Checks that the production NeoForge jar contains exactly one current-host JNI resource."
        group = "verification"
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        inputs.property("nativeResourcePath", nativeResourcePath)
        doLast {
            val archive = productionJar.get().archiveFile.get().asFile
            val nativeEntries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .filter { it.startsWith("META-INF/natives/") }
                        .toList()
                }
            check(nativeEntries == listOf(nativeResourcePath)) {
                "expected only $nativeResourcePath in ${archive.name}, found $nativeEntries"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPackagedCompukterJni)
}

tasks.named("buildProductionUniversalJar") {
    dependsOn(verifyPackagedCompukterJni)
}
