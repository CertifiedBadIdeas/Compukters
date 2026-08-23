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

plugins {
    id("kotlin-convention")
    id("dev.architectury.loom")
    id("loom-runs-convention")
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val libs = libsCatalog()

setLoaderKind(LoaderKind.NEOFORGE)

// Loader-leaf archive version = "<mc>-<loader>-<modVersion>".
version = computeModArchiveVersion()

architectury {
    platformSetupLoomIde()
    neoForge()
}

val common: Configuration by configurations.creating
val shadowBundle: Configuration by configurations.creating

configurations {
    compileClasspath { extendsFrom(common) }
    runtimeClasspath { extendsFrom(common) }
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))
    modImplementation(versionLibrary("architectury-neoforge"))

    implementation(project(":native-runtime"))
    shadowBundle(project(path = ":native-runtime")) { isTransitive = false }

    implementation(project(":core"))
    shadowBundle(project(":core")) { isTransitive = false }

    neoForgeImplementation(libs.findLibrary("kotlin-stdlib").get())
    neoForgeImplementation(libs.findLibrary("kotlin-logging").get())
    neoForgeImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("dev-shadow")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
    dependsOn(tasks.named("shadowJar"))
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: Provider<T>) {
    val resolvedDependency = dependency.get()
    val implementationDependency = create(resolvedDependency) as ModuleDependency
    val runtimeDependency = create(resolvedDependency) as ModuleDependency
    val includedDependency = create(resolvedDependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    runtimeDependency.isTransitive = false
    add("forgeRuntimeLibrary", runtimeDependency)
    include(includedDependency)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: T) {
    val implementationDependency = create(dependency) as ModuleDependency
    val runtimeDependency = create(dependency) as ModuleDependency
    val includedDependency = create(dependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    runtimeDependency.isTransitive = false
    add("forgeRuntimeLibrary", runtimeDependency)
    include(includedDependency)
}
