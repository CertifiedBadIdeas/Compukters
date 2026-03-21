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

import org.gradle.jvm.tasks.Jar

// operator fun Provider<String>.getValue(ref: Any?, prop: KProperty<*>): String = this.get()
//
// fun File.parseProperties(): Map<String, String> =
//    readLines()
//        .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
//        .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }
//
// val modPropertiesFile = file("config/mod.properties")
// val modPropertiesDelegate = extra.properties.mapValues { (_, v) -> v.toString() }
//
// val mod_id by modPropertiesDelegate
// val mod_name by modPropertiesDelegate
// val mod_license by modPropertiesDelegate
// val mod_authors by modPropertiesDelegate
// val mod_description by modPropertiesDelegate
// val mod_version by modPropertiesDelegate
// val mod_group_id by modPropertiesDelegate
// val minecraft_version by libs.versions.minecraft
// val minecraft_version_range by modPropertiesDelegate
// val forge_version by modPropertiesDelegate
// val forge_version_range by modPropertiesDelegate
// val loader_version_range by modPropertiesDelegate
// val parchment_mappings_version by modPropertiesDelegate
// val parchment_minecraft_version by modPropertiesDelegate

plugins {
    idea
    alias(libs.plugins.kotlinConvention)
    alias(libs.plugins.architectury.loom)
    alias(libs.plugins.architectury.plugin)
}

architectury {
    minecraft = libs.versions.minecraft.get()

    platformSetupLoomIde()
    forge()
}

dependencies {
    minecraft(libs.minecraft)
    forge(libs.forge)
    mappings(
        loom.layered {
            officialMojangMappings()
            parchment(libs.parchment.for1v20v1)
        },
    )
    modImplementation(libs.architectury.forge)

    implementation(libs.kotlinx.coroutines.core)
    forgeRuntimeLibrary(libs.kotlinx.coroutines.core)

    implementation(projects.scriptingApi)
    forgeRuntimeLibrary(projects.scriptingApi)

    implementation(libs.kotlin.stdlib)
    forgeRuntimeLibrary(libs.kotlin.stdlib)

    implementation(libs.kotlin.logging)
    forgeRuntimeLibrary(libs.kotlin.logging)
}

val copyScriptingJar =
    tasks.register<Copy>("copyScriptingJar") {
        val scriptingShadowJar = project(projects.scripting.path).tasks.named<Jar>("shadowJar")

        dependsOn(scriptingShadowJar)
        from(scriptingShadowJar.map { it.archiveFile })
        into(layout.projectDirectory.dir("run/compukterkraft"))
        rename { "CompukterKraftScripting.jar" }
    }

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val modPropertiesFile = file("$rootDir/config/mod.properties")
        val replaceProperties =
            modPropertiesFile
                .readLines()
                .mapNotNull { it.indexOf('=').takeIf { i -> i != -1 }?.let { v -> v to it } }
                .associate { (index, str) -> str.substring(0, index) to str.substring(index + 1) }
        val from = file("src/main/resources")
        val intoDir = file("build/generated/resources")

        inputs.file(modPropertiesFile)
        inputs.properties(replaceProperties)
        inputs.dir(from)

        outputs.dir(intoDir)

        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateModMetadata)
}

tasks.matching { it.name.startsWith("run") }.configureEach {
    dependsOn(copyScriptingJar)
}

sourceSets.main {
    resources.setSrcDirs(generateModMetadata.get().outputs.files)
}
