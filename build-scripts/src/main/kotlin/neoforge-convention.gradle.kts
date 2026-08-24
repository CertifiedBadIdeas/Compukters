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
    id("dev.architectury.loom-no-remap")
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

val common = configurations.create("common")
val shadowBundle = configurations.create("shadowBundle")

configurations {
    compileClasspath { extendsFrom(common) }
    runtimeClasspath { extendsFrom(common) }
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))

    implementation(project(":native-runtime"))
    shadowBundle(project(path = ":native-runtime")) { isTransitive = false }

    implementation(project(":core"))
    shadowBundle(project(":core")) { isTransitive = false }

    implementation(project(":compiler-client"))
    shadowBundle(project(":compiler-client")) { isTransitive = false }

    implementation(project(":compiler-runtime"))
    shadowBundle(project(":compiler-runtime")) { isTransitive = false }

    neoForgeImplementation(libs.findLibrary("kotlin-stdlib").get())
    neoForgeImplementation(libs.findLibrary("kotlin-logging").get())
    neoForgeImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

val productionJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

loom {
    nestJars(productionJar, configurations.named("include"))
}

tasks.named("assemble") {
    dependsOn(productionJar)
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
