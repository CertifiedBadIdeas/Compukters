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
base { archivesName.set("compukters-propulsion-1.21.1-neoforge") }
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
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/snapshots")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven")
}
apply(from = "../sable/gradle/sable-libraries.gradle.kts")
// Propulsion 1.1.5 also uses Simulated classes directly, despite omitting it from required metadata.
apply(from = "../dev/gradle/aeronautics-libraries.gradle.kts")
val aeronauticsLibraries = files(configurations.named("aeronauticsNestedMods"))
val sableCompanion = files(configurations.named("sableCompanion"))
val sableLibraries = files(configurations.named("sableNestedMods"))
val veilLibraries = files(configurations.named("sableRuntimeLibraries"))

apply(from = "../gradle/workspace-sdk.gradle.kts")
compuktersAddon { register("propulsion") }

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
    modImplementation("maven.modrinth:sable:fg9dTRz9")
    modImplementation("maven.modrinth:create-propulsion-simulated:H13U56dc") { isTransitive = false }
    modImplementation("com.simibubi.create:create-1.21.1:6.0.10-280:slim") { isTransitive = false }
    modImplementation("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1")
    modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6")
    modImplementation("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")
    compileOnly(sableCompanion)
    // Loom dev runs do not discover Sable's Jar-in-Jar runtime dependencies automatically.
    modRuntimeOnly(sableLibraries)
    modRuntimeOnly("maven.modrinth:create-aeronautics:Vzp221Un") { isTransitive = false }
    modRuntimeOnly(aeronauticsLibraries)
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
    mods { maybeCreate("compukters_propulsion").sourceSet("main") }
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
                maybeCreate("compukters_propulsion").apply {
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
    archiveFileName.set("compukters-propulsion-development-mod.jar")
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
    archiveFileName.set("compukters-propulsion-gametest-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
    from(gameTest.output)
}

val productionJar = tasks.named<RemapJarTask>("remapJar") {
    inputFile.set(tasks.jar.flatMap { it.archiveFile })
    archiveClassifier.set("")
}
val verifyProductionJar = tasks.register("verifyProductionJar") {
    group = "verification"
    dependsOn(productionJar)
    inputs.file(productionJar.flatMap { it.archiveFile })
    doLast {
        val archive = productionJar.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            listOf(
                "META-INF/neoforge.mods.toml",
                "META-INF/compukters/addons/propulsion.cagb",
                "compukters_propulsion.mixins.json",
                "ru/lazyhat/compukters/integration/propulsion/CompuktersPropulsionMod.class",
                "ru/lazyhat/compukters/integration/propulsion/PropulsionGuestIntegration.class",
                "ru/lazyhat/compukters/integration/propulsion/mixin/TransientThrusterControlMixin.class",
                "ru/lazyhat/compukters/integration/propulsion/mixin/VectorThrusterControlMixin.class",
                "ru/lazyhat/compukters/integration/propulsion/mixin/CreativeVectorThrustMixin.class",
            ).forEach { required ->
                check(entries.count { it == required } == 1) { "$required is missing or duplicated in ${archive.name}" }
            }
            listOf(
                "dev/propulsionteam/", "dev/ryanhcode/", "com/simibubi/create/",
                "ru/lazyhat/compukters/api/", "ru/lazyhat/compukters/core/", "ru/lazyhat/compukters/impl/",
                "ru/lazyhat/compukters/integration/create/", "ru/lazyhat/compukters/integration/sable/",
            ).forEach { forbidden ->
                check(entries.none { it.startsWith(forbidden) }) { "$forbidden implementation leaked into ${archive.name}" }
            }
            check(entries.none { it.contains("GameTest") || it.contains("GuestComputerScenario") }) {
                "GameTest implementation leaked into ${archive.name}"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyProductionJar) }
tasks.named("assemble") { dependsOn(productionJar) }
tasks.register("buildProductionJar") {
    group = "build"
    dependsOn(productionJar, verifyProductionJar)
}
