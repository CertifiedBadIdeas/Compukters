/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.zip.ZipFile

plugins {
    java
    id("dev.architectury.loom")
    id("architectury-plugin")
}

group = "ru.lazyhat.compukters.development"
version = "development"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
architectury {
    minecraft = "1.21.1"
    platformSetupLoomIde()
    neoForge()
}
repositories {
    mavenCentral()
    maven("https://maven.architectury.dev/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/snapshots")
}
apply(from = "../sable/gradle/sable-libraries.gradle.kts")
apply(from = "gradle/aeronautics-libraries.gradle.kts")
val aeronauticsLibraries = files(configurations.named("aeronauticsNestedMods"))
val physicsMods = configurations.create("physicsMods") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val sableLibraries = files(configurations.named("sableNestedMods"))
val veilLibraries = files(configurations.named("sableRuntimeLibraries"))

val baseMod = files(rootProject.file("../../modules/minecraft/v1_21_1/v1_21_1-neoforge/build/devlibs/compukters-development-mod.jar"))
    .builtBy(gradle.includedBuild("Compukters").task(":v1_21_1-neoforge:developmentModJar"))
val addonMods = mapOf(
    "create" to "compukters-create",
    "sable" to "compukters-sable",
    "propulsion" to "compukters-propulsion",
).mapValues { (directory, buildName) ->
    files(rootProject.file("../$directory/build/devlibs/$buildName-development-mod.jar"))
        .builtBy(gradle.includedBuild(buildName).task(":developmentModJar"))
}
val addonGameTestMods = mapOf(
    "create" to "compukters-create",
    "sable" to "compukters-sable",
    "propulsion" to "compukters-propulsion",
).mapValues { (directory, buildName) ->
    files(rootProject.file("../$directory/build/devlibs/$buildName-gametest-mod.jar"))
        .builtBy(gradle.includedBuild(buildName).task(":developmentGameTestModJar"))
}
val gameTest by sourceSets.creating
// Select complete test archives instead of loading duplicate copies of each addon.
gameTest.compileClasspath += sourceSets.main.get().compileClasspath
val normalAddonPaths = addonMods.values.map { it.singleFile.absolutePath }.toSet()
gameTest.runtimeClasspath = sourceSets.main.get().runtimeClasspath.filter { it.absolutePath !in normalAddonPaths } +
    files(addonGameTestMods.values) + gameTest.output

dependencies {
    minecraft("net.minecraft:minecraft:1.21.1")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip")
    })
    neoForge("net.neoforged:neoforge:21.1.252")
    runtimeOnly(baseMod)
    addonMods.values.forEach { runtimeOnly(it) }
    physicsMods("maven.modrinth:create-aeronautics:Vzp221Un")
    physicsMods("maven.modrinth:create-propulsion-simulated:H13U56dc")
    modRuntimeOnly(files(physicsMods))
    modRuntimeOnly(aeronauticsLibraries)
    modRuntimeOnly("maven.modrinth:sable:U678xqle")
    modRuntimeOnly(sableLibraries)
    forgeRuntimeLibrary(veilLibraries)
    modRuntimeOnly("com.simibubi.create:create-1.21.1:6.0.10-280:slim") { isTransitive = false }
    modRuntimeOnly("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6")
    modRuntimeOnly("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")
    listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
        "io.github.oshai:kotlin-logging-jvm:8.0.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
        "org.tukaani:xz:1.12",
    ).forEach { forgeRuntimeLibrary(it) { isTransitive = false } }
}
loom {
    mods { maybeCreate("compukters_addons_dev").sourceSet("main") }
    runs {
        named("client") { runDir("run/client"); ideConfigGenerated(true); programArgs("--username", "DevA") }
        named("server") { runDir("run/server"); ideConfigGenerated(true) }
        register("gameTestServer") {
            server()
            source(gameTest)
            environment("gametestserver")
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "minecraft")
            ideConfigGenerated(true)
            mods { maybeCreate("compukters_addons_dev").sourceSet("main") }
        }
    }
}
// This is a run/test composition, never a distributable umbrella mod.
tasks.jar { enabled = false }
val verifyDevelopmentMods = tasks.register("verifyDevelopmentMods") {
    group = "verification"
    dependsOn(baseMod, addonMods.values)
    inputs.files(baseMod, addonMods.values)
    doLast {
        addonMods.forEach { (id, archive) ->
            ZipFile(archive.singleFile).use { zip ->
                val metadata = checkNotNull(zip.getEntry("META-INF/neoforge.mods.toml"))
                val text = zip.getInputStream(metadata).bufferedReader().use { it.readText() }
                check(text.contains("modId=\"compukters_$id\"")) { "incorrect addon archive for $id" }
                val other = addonMods.keys - id
                check(zip.entries().asSequence().none { entry ->
                    other.any { entry.name.startsWith("ru/lazyhat/compukters/integration/$it/") }
                }) {
                    "addon implementations must remain independent"
                }
            }
        }
        check(baseMod.singleFile.isFile) { "base development mod is missing" }
    }
}
val verifyPhysicsMods = tasks.register("verifyPhysicsMods") {
    group = "verification"
    description = "Verify the exact Aeronautics bundle and Propulsion mods used by every development run."
    inputs.files(physicsMods, aeronauticsLibraries)
    doLast {
        val ids = (physicsMods.files + aeronauticsLibraries.files).map { archive ->
            ZipFile(archive).use { zip ->
                val metadata = checkNotNull(zip.getEntry("META-INF/neoforge.mods.toml"))
                val text = zip.getInputStream(metadata).bufferedReader().use { it.readText() }
                checkNotNull(Regex("(?m)^modId\\s*=\\s*\"([^\"]+)\"").find(text)).groupValues[1]
            }
        }
        check(ids.size == ids.toSet().size) { "duplicate physics mod IDs: $ids" }
        check(ids.toSet() == setOf("aeronautics_bundled", "aeronautics", "offroad", "simulated", "createpropulsion")) {
            "incomplete physics runtime: $ids"
        }
        logger.lifecycle("Development physics mods: ${ids.sorted().joinToString()}")
    }
}
tasks.named("check") { dependsOn(verifyDevelopmentMods, verifyPhysicsMods) }
tasks.register("verifyAddons") {
    group = "verification"
    dependsOn(verifyDevelopmentMods, verifyPhysicsMods)
    dependsOn(gradle.includedBuild("compukters-create").task(":check"))
    dependsOn(gradle.includedBuild("compukters-sable").task(":check"))
    dependsOn(gradle.includedBuild("compukters-propulsion").task(":check"))
}

tasks.configureEach {
    if (name == "runClient" || name == "runServer" || name == "runGameTestServer") dependsOn(verifyPhysicsMods)
    if (name == "runGameTestServer") dependsOn(addonGameTestMods.values)
}
