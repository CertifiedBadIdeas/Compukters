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

import net.fabricmc.loom.task.RemapJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("org.jmailen.kotlinter")
    id("ru.lazyhat.compukters.addon")
}

group = "ru.lazyhat.compukters"
val addonSdkVersion = "0.4.0"
version = providers.gradleProperty("addonVersion").get()

base {
    archivesName.set("compukters-create-1.21.1-neoforge")
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

architectury {
    minecraft = "1.21.1"
    platformSetupLoomIde()
    neoForge()
}

apply(from = "../gradle/workspace-sdk.gradle.kts")

compuktersAddon {
    register("create")
}

val gameTest by sourceSets.creating
kotlin.target.compilations.named(gameTest.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}
configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.architectury.dev/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/snapshots")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven")
}

val compuktersCommonApi = "ru.lazyhat.compukters:compukters-addon-api:$addonSdkVersion"
val compuktersAdapterApi = "ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1:$addonSdkVersion"
val compuktersDevelopmentMod =
    files(rootProject.file("../../modules/minecraft/v1_21_1/v1_21_1-neoforge/build/devlibs/compukters-development-mod.jar"))
        .builtBy(gradle.includedBuild("Compukters").task(":v1_21_1-neoforge:developmentModJar"))

dependencies {
    minecraft("net.minecraft:minecraft:1.21.1")
    mappings(
        loom.layered {
            officialMojangMappings()
            parchment("org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip")
        },
    )
    neoForge("net.neoforged:neoforge:21.1.252")

    runtimeOnly(compuktersDevelopmentMod)
    add(gameTest.implementationConfigurationName, compuktersDevelopmentMod)
    testImplementation(compuktersCommonApi)
    testImplementation(compuktersAdapterApi)
    modImplementation("com.simibubi.create:create-1.21.1:6.0.10-280:slim") { isTransitive = false }
    modImplementation("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1")
    modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6")
    modImplementation("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")

    listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
        "io.github.oshai:kotlin-logging-jvm:8.0.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
        "org.tukaani:xz:1.12",
    ).forEach { dependency ->
        forgeRuntimeLibrary(dependency) { isTransitive = false }
    }

    testImplementation(kotlin("test"))
}

loom {
    mods {
        maybeCreate("compukters_create").sourceSet("main")
    }
    runs {
        named("client") {
            runDir("run/client")
            ideConfigGenerated(true)
            programArgs("--username", "DevA")
        }
        named("server") {
            runDir("run/server")
            ideConfigGenerated(true)
        }
        register("gameTestServer") {
            server()
            source(gameTest)
            environment("gametestserver")
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "minecraft")
            ideConfigGenerated(true)
            mods {
                maybeCreate("compukters_create").apply {
                    sourceSet("main")
                    sourceSet(gameTest.name)
                }
            }
        }
    }
}

tasks.processResources {
    inputs.property("addonVersion", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("addon_version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ConfigurableKtLintTask>().configureEach {
    exclude { it.file.path.contains("build/generated") }
}

tasks.jar {
    archiveClassifier.set("dev")
}

val productionJar =
    tasks.named<RemapJarTask>("remapJar") {
        inputFile.set(tasks.jar.flatMap { it.archiveFile })
        archiveClassifier.set("")
    }

val verifyProductionJar =
    tasks.register("verifyProductionJar") {
        group = "verification"
        description = "Checks that the standalone Create addon contains only its own implementation and Guest API bundle."
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        doLast {
            val archive =
                productionJar
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .toList()
                }
            listOf(
                "META-INF/neoforge.mods.toml",
                "ru/lazyhat/compukters/integration/create/CompuktersCreateMod.class",
                "ru/lazyhat/compukters/integration/create/CreatePeripheralIntegration.class",
                "META-INF/compukters/addons/create.cagb",
            ).forEach { required ->
                check(entries.count { it == required } == 1) { "$required is missing or duplicated in ${archive.name}" }
            }
            check(entries.none { it.startsWith("com/simibubi/create/") }) { "Create implementation classes leaked into ${archive.name}" }
            check(entries.none { it.startsWith("ru/lazyhat/compukters/api/") }) { "Compukters API classes leaked into ${archive.name}" }
            check(entries.none { it.startsWith("ru/lazyhat/compukters/core/") }) { "Compukters core classes leaked into ${archive.name}" }
            check(entries.none { it.startsWith("ru/lazyhat/compukters/impl/") }) { "Compukters implementation leaked into ${archive.name}" }
            check(entries.none { it.startsWith("ru/lazyhat/compukters/integration/create/gametest/") }) {
                "Create GameTest classes leaked into ${archive.name}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyProductionJar, gameTest.classesTaskName)
}

tasks.configureEach {
    if (name == "runGameTestServer") dependsOn(gameTest.classesTaskName)
}

val verifyGameTestRunIsolation =
    tasks.register("verifyGameTestRunIsolation") {
        group = "verification"
        description = "Checks that Create GameTests are visible only to their dedicated server run."
        doLast {
            val outputs = gameTest.output.files.map(File::getCanonicalFile).toSet()
            fun modFiles(runName: String): Set<File> {
                val run = loom.runs.named(runName).get()
                return (if (run.mods.isEmpty()) loom.mods else run.mods)
                    .flatMap { it.modFiles.files }.map(File::getCanonicalFile).toSet()
            }
            listOf("client", "server").forEach { runName ->
                check(modFiles(runName).intersect(outputs).isEmpty()) { "Create GameTest output leaked into $runName" }
            }
            check(modFiles("gameTestServer").intersect(outputs).isNotEmpty()) { "Create GameTest output is missing" }
        }
    }

tasks.named("check") {
    dependsOn(verifyGameTestRunIsolation)
}

tasks.named("assemble") {
    dependsOn(productionJar)
}

tasks.register("buildProductionJar") {
    group = "build"
    description = "Builds the standalone remapped Create addon mod JAR."
    dependsOn(productionJar, verifyProductionJar)
}

// Stable development archive consumed by the independent all-addon run build.
val developmentModJar = tasks.register<Jar>("developmentModJar") {
    group = "build"
    archiveFileName.set("compukters-create-development-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
}

// Test composition archive; independent from the normal addon development archive.
val developmentGameTestModJar = tasks.register<Jar>("developmentGameTestModJar") {
    group = "verification"
    archiveFileName.set("compukters-create-gametest-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
    from(gameTest.output)
}
