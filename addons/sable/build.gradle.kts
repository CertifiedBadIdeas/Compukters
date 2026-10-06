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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("org.jmailen.kotlinter")
    id("ru.lazyhat.compukters.addon")
}

group = "ru.lazyhat.compukters"
base { archivesName.set("compukters-sable-1.21.1-neoforge") }
apply(from = "../gradle/addon-versioning.gradle.kts")

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
architectury {
    minecraft = "1.21.1"
    platformSetupLoomIde()
    neoForge()
}
repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.architectury.dev/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org/")
    maven("https://api.modrinth.com/maven")
}
apply(from = "gradle/sable-libraries.gradle.kts")
val sableCompanion = files(configurations.named("sableCompanion"))
val sableLibraries = files(configurations.named("sableNestedMods"))
val veilLibraries = files(configurations.named("sableRuntimeLibraries"))

apply(from = "../gradle/workspace-sdk.gradle.kts")
compuktersAddon { register("sable") }

val gameTest by sourceSets.creating
kotlin.target.compilations.named(gameTest.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}
configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

val developmentMod =
    files(rootProject.file("../../modules/minecraft/v1_21_1/v1_21_1-neoforge/build/devlibs/compukters-development-mod.jar"))
        .builtBy(gradle.includedBuild("Compukters").task(":v1_21_1-neoforge:developmentModJar"))
dependencies {
    minecraft("net.minecraft:minecraft:1.21.1")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip")
    })
    neoForge("net.neoforged:neoforge:21.1.252")
    modImplementation("maven.modrinth:sable:U678xqle")
    compileOnly(sableCompanion)
    // Loom dev runs do not discover Sable's Jar-in-Jar runtime dependencies automatically.
    modRuntimeOnly(sableLibraries)
    forgeRuntimeLibrary(veilLibraries)
    compileOnly("ru.lazyhat.compukters:compukters-addon-api:0.5.0")
    compileOnly("ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1:0.5.0")
    runtimeOnly(developmentMod)
    add(gameTest.implementationConfigurationName, developmentMod)
    listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
        "io.github.oshai:kotlin-logging-jvm:8.0.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
        "org.tukaani:xz:1.12",
    ).forEach { forgeRuntimeLibrary(it) { isTransitive = false } }
    testImplementation(kotlin("test"))
}
loom {
    mods { maybeCreate("compukters_sable").sourceSet("main") }
    runs {
        named("client") { runDir("run/client"); ideConfigGenerated(true) }
        named("server") { runDir("run/server"); ideConfigGenerated(true) }
        register("gameTestServer") {
            server()
            source(gameTest)
            environment("gametestserver")
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "minecraft")
            ideConfigGenerated(true)
            mods {
                maybeCreate("compukters_sable").apply {
                    sourceSet("main")
                    sourceSet(gameTest.name)
                }
            }
        }
    }
}
tasks.test { useJUnitPlatform() }
tasks.withType<ConfigurableKtLintTask>().configureEach { exclude { it.file.path.contains("build/generated") } }
tasks.jar { archiveClassifier.set("dev") }

// Stable development archive consumed by the independent all-addon run build.
val developmentModJar = tasks.register<Jar>("developmentModJar") {
    group = "build"
    archiveFileName.set("compukters-sable-development-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
}

tasks.named("check") { dependsOn(gameTest.classesTaskName) }
tasks.configureEach {
    if (name == "runGameTestServer") dependsOn(gameTest.classesTaskName)
}

// Test composition archive; independent from the normal addon development archive.
val developmentGameTestModJar = tasks.register<Jar>("developmentGameTestModJar") {
    group = "verification"
    archiveFileName.set("compukters-sable-gametest-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
    from(gameTest.output)
}
