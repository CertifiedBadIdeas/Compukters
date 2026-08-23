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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Locale
import java.util.zip.ZipFile

plugins {
    idea
    alias(libs.plugins.v261)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.metadataConvention)
}

val gameTest by sourceSets.creating

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

val terminalFixture = rootProject.file("host/compukter-vm/tests/fixtures/terminal-session.hex")

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

val verifyGameTestRunIsolation =
    tasks.register("verifyGameTestRunIsolation") {
        group = "verification"
        description = "Checks that GameTest classes are visible only to the GameTest run."
        doLast {
            val gameTestFiles = gameTest.output.files.map(File::getCanonicalFile).toSet()

            fun effectiveModFiles(runName: String): Set<File> {
                val run = loom.runs.named(runName).get()
                val mods = if (run.mods.isEmpty()) loom.mods else run.mods
                return mods.flatMap { it.modFiles.files }.map(File::getCanonicalFile).toSet()
            }

            listOf("client", "client2", "client3", "server").forEach { runName ->
                val leaked = effectiveModFiles(runName).intersect(gameTestFiles)
                check(leaked.isEmpty()) { "GameTest output leaked into $runName: $leaked" }
            }
            check(effectiveModFiles("gameTestServer").intersect(gameTestFiles).isNotEmpty()) {
                "GameTest output is missing from gameTestServer"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGameTestRunIsolation)
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
        matching { it.name.startsWith("client") }.configureEach {
            property("compukter.vm.devTerminalFixture", terminalFixture.absolutePath)
        }

        register("gameTestServer") {
            server()
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "compukters")
            property("compukter.vm.terminalFixture", terminalFixture.absolutePath)
            ideConfigGenerated(true)
            vmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
            mods {
                maybeCreate("main").apply {
                    sourceSet("main")
                    sourceSet("main", projects.v261Common.path)
                    sourceSet(gameTest.name)
                }
            }
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v261Common.path))
        }
    }
}

dependencies {
    common(project(path = projects.v261Common.path)) { isTransitive = false }
    shadowBundle(project(path = projects.v261Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v261Common.path))

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v261Common.path))
}

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
        "linux" -> "libcompukter_ffi.so"
        "windows" -> "compukter_ffi.dll"
        "macos" -> "libcompukter_ffi.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val productionJar = tasks.named<ShadowJar>("shadowJar")
val verifyPackagedCompukterFfi =
    tasks.register("verifyPackagedCompukterFfi") {
        description = "Checks the contents of the production NeoForge jar."
        group = "verification"
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        inputs.property("nativeResourcePath", nativeResourcePath)
        doLast {
            val archive = productionJar.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .toList()
                }
            val nativeEntries = entries.filter { it.startsWith("META-INF/natives/") }
            check(nativeEntries == listOf(nativeResourcePath)) {
                "expected only $nativeResourcePath in ${archive.name}, found $nativeEntries"
            }
            check(entries.count { it == "META-INF/neoforge.mods.toml" } == 1) {
                "expected exactly one META-INF/neoforge.mods.toml in ${archive.name}"
            }
            check(entries.none { it.startsWith("dev/architectury/") }) {
                "Architectury runtime classes leaked into ${archive.name}"
            }
            check(entries.none { it.contains("kotlin/compiler") }) {
                "Kotlin compiler implementation leaked into ${archive.name}"
            }
            check(entries.none { it.contains("ComputerBlockGameTest") }) {
                "GameTest classes leaked into ${archive.name}"
            }
            check("ru/lazyhat/compukters/minecraft/computer/ComputerBlock.class" in entries) {
                "common computer classes are missing from ${archive.name}"
            }
            check("ru/lazyhat/compukters/impl/computer/NeoForgeComputerBlockEntity.class" in entries) {
                "NeoForge computer classes are missing from ${archive.name}"
            }
            check("assets/compukters/items/compukter.json" in entries) {
                "26.1 item model is missing from ${archive.name}"
            }
            check("assets/compukters/models/item/compukter.json" !in entries) {
                "legacy item model leaked into ${archive.name}"
            }
            check("pack.mcmeta" !in entries) {
                "legacy pack.mcmeta leaked into ${archive.name}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPackagedCompukterFfi)
}

tasks.named("buildProductionUniversalJar") {
    dependsOn(verifyPackagedCompukterFfi)
}

val verifyNeoForgeRuntimeDependencies =
    tasks.register("verifyNeoForgeRuntimeDependencies") {
        description = "Rejects Architectury mod runtime and embedded Kotlin compiler dependencies."
        group = "verification"
        val runtimeClasspath = configurations.named("runtimeClasspath")
        inputs.files(runtimeClasspath)
        doLast {
            val forbidden =
                runtimeClasspath
                    .get()
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .filter { component ->
                        (component.group.startsWith("dev.architectury") &&
                            component.module != "architectury-transformer") ||
                            component.module.contains("kotlin-compiler")
                    }.map(ModuleComponentIdentifier::getDisplayName)
                    .sorted()
            check(forbidden.isEmpty()) {
                "forbidden NeoForge runtime dependencies: ${forbidden.joinToString()}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyNeoForgeRuntimeDependencies)
}
