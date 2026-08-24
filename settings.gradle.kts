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

@file:OptIn(InternalRefreshVersionsApi::class)
@file:Suppress("ktlint:standard:property-naming", "PropertyName")

import de.fayard.refreshVersions.core.internal.InternalRefreshVersionsApi

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_25)) {
    "Compukters requires Gradle to run on JDK 25 or newer; current JVM is ${System.getProperty("java.version")}. " +
        "Set JAVA_HOME to a JDK 25 installation and retry."
}

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    id("de.fayard.refreshVersions") version "0.60.6"
}

includeBuild("build-scripts")
val modulesDir = rootDir.resolve("modules")

fun include(
    path: String,
    dir: File,
) {
    include(path)
    project(":$path").apply {
        projectDir = dir.resolve(path)
    }
}

include("native-runtime", modulesDir)
include("compiler-artifact", modulesDir)
include("compiler-client", modulesDir)
include("compiler-runtime", modulesDir)
include("compiler-k2", modulesDir)
include("guest-api-core", modulesDir)
include("playground", modulesDir)
include("core", modulesDir)

val v26_1Dir = modulesDir.resolve("v26_1")
include("v26_1-common", v26_1Dir)
include("v26_1-neoforge", v26_1Dir)

rootProject.name = "Compukters"
