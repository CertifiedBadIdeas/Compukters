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

setLoaderKind(LoaderKind.FABRIC)

// Loader-leaf archive version = "<mc>-<loader>-<modVersion>".
version = computeModArchiveVersion()

architectury {
    platformSetupLoomIde()
    fabric()
}

dependencies {
    modImplementation(versionLibrary("fabric-loader"))
    modImplementation(versionLibrary("fabric-api"))
    modImplementation(versionLibrary("architectury-fabric"))

    implementation(project(":core"))

    fabricImplementation(project(":compiler"))
    fabricImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
    fabricImplementation(libs.findLibrary("kotlinx-collections-immutable").get())
    fabricImplementation(libs.findLibrary("kotlin-stdlib").get())
    fabricImplementation(libs.findLibrary("kotlin-logging").get())
}

fun <T : ModuleDependency> DependencyHandler.fabricImplementation(dependency: Provider<T>) {
    val resolvedDependency = dependency.get()
    val implementationDependency = create(resolvedDependency) as ModuleDependency
    val includedDependency = create(resolvedDependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    include(includedDependency)
}

fun <T : ModuleDependency> DependencyHandler.fabricImplementation(dependency: T) {
    val implementationDependency = create(dependency) as ModuleDependency
    val includedDependency = create(dependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    include(includedDependency)
}