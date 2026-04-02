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

plugins {
    idea
    alias(libs.plugins.kotlinConvention)
    alias(libs.plugins.architectury.loom)
    alias(libs.plugins.architectury.plugin)
}

architectury {
    minecraft =
        libs.versions.minecraft.v1211
            .get()

    platformSetupLoomIde()
    neoForge()
}

repositories {
    maven("https://maven.neoforged.net/releases/")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val modProperties =
    file("$rootDir/config/mod.properties")
        .readLines()
        .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
        .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }
        .toMutableMap()

val minecraftVersion =
    libs.versions.minecraft.v1211
        .get()
val modVersion = "$minecraftVersion-${rootProject.version}"
modProperties["mod_version"] = modVersion
modProperties["minecraft_version_range"] = "[1.21.1, 1.22)"
modProperties["neoforge_version_range"] = "[21.1,)"
modProperties["loader_version_range"] = "[4,)"

base.archivesName = modProperties["mod_name"]!!.replace(" ", "")
version = modVersion

tasks.register("listConfigs") {
    doLast {
        configurations.names.sorted().forEach { println(it) }
    }
}

dependencies {
    minecraft(libs.minecraft.v1211)
    neoForge(libs.neoforge)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.architectury.neoforge)

    neoForgeImplementation(projects.compiler)
    neoForgeImplementation(libs.kotlinx.coroutines.core)
    neoForgeImplementation(libs.kotlin.stdlib)
    neoForgeImplementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: Provider<T>) {
    implementation(dependency) {
        isTransitive = false
    }
    forgeRuntimeLibrary(dependency) {
        isTransitive = false
    }
    include(dependency)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: T) {
    implementation(dependency) {
        isTransitive = false
    }
    forgeRuntimeLibrary(dependency) {
        isTransitive = false
    }
    include(dependency)
}

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val replaceProperties = modProperties.toMap()
        val from = file("src/main/resources")
        val intoDir = file("build/generated/resources")

        inputs.properties(replaceProperties)
        inputs.dir(from)

        outputs.dir(intoDir)

        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
    }

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.srcDir(generateModMetadata.get().outputs.files)
}
