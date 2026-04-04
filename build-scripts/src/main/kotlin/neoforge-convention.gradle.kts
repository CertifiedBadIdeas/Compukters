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

plugins {
    id("kotlin-convention")
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val libs = libsCatalog()

setLoaderKind(LoaderKind.NEOFORGE)

architectury {
    platformSetupLoomIde()
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))
    modImplementation(versionLibrary("architectury-neoforge"))

    implementation(project(":core"))

    neoForgeImplementation(project(":compiler"))
    neoForgeImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
    neoForgeImplementation(libs.findLibrary("kotlin-stdlib").get())
    neoForgeImplementation(libs.findLibrary("kotlin-logging").get())
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