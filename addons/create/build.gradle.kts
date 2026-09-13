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
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.neoforgeAddon1211Convention)
    alias(libs.plugins.addonGuestApiConvention)
}

repositories {
    maven("https://maven.createmod.net") {
        name = "Create"
    }
    maven("https://maven.ithundxr.dev/snapshots") {
        name = "Registrate"
    }
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven") {
        name = "NeoForgeConfigApiPort"
    }
}

dependencies {
    implementation(projects.v1211Common)
    modImplementation(variantOf(libs.create.v1211) { classifier("slim") }) {
        isTransitive = false
    }
    modImplementation(libs.ponder.v1211)
    modCompileOnly(libs.flywheel.api.v1211)
    modRuntimeOnly(libs.flywheel.v1211)
    modImplementation(libs.registrate.v1211)
    testImplementation(projects.compilerArtifact)
    testImplementation(projects.compilerClient)
    testImplementation(projects.compilerK2)
    testImplementation(projects.platformBundle)
    testImplementation(kotlin("test"))
}

loom {
    mods {
        maybeCreate("compukters_create").sourceSet("main")
    }
}

val createKineticsAddonBundle = layout.buildDirectory.file("addon-guest-api/${project.name}.cagb")
val compilerWorkerJar = configurations.create("compilerWorkerJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(compilerWorkerJar.name, project(path = projects.compilerK2.path)) {
        isTransitive = false
    }
}

tasks.processResources {
    dependsOn("assembleAddonGuestApiBundle")
    inputs.property("addonVersion", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("addon_version" to project.version)
    }
    from(createKineticsAddonBundle) {
        into("META-INF/compukters/addons")
        rename { "create-kinetics.cagb" }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn("assembleAddonGuestApiBundle", compilerWorkerJar)
    inputs.file(createKineticsAddonBundle)
    inputs.files(compilerWorkerJar)
    doFirst {
        systemProperty("compukters.test.createKineticsGuestApi", createKineticsAddonBundle.get().asFile.absolutePath)
        systemProperty("compukters.test.compilerWorkerJar", compilerWorkerJar.singleFile.absolutePath)
    }
}

val createKineticsConformanceArtifact = layout.buildDirectory.file("generated/conformance/create-kinetics.cpkt")
tasks.register<Test>("generateCreateKineticsConformanceArtifact") {
    description = "Compiles the deterministic Create kinetics program for GameTest conformance."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*Create kinetics program lowers deterministically for GameTest conformance*")
    outputs.file(createKineticsConformanceArtifact)
    doFirst {
        systemProperty("compukter.vm.createKineticsArtifact", createKineticsConformanceArtifact.get().asFile.absolutePath)
    }
}

tasks.jar {
    archiveClassifier.set("dev")
}

val productionJar = tasks.named<RemapJarTask>("remapJar") {
    inputFile.set(tasks.jar.flatMap { it.archiveFile })
    archiveClassifier.set("")
}

val verifyProductionJar = tasks.register("verifyProductionJar") {
    group = "verification"
    description = "Checks that the standalone Create addon contains its mod entry point and Guest API bundle only once."
    dependsOn(productionJar)
    inputs.file(productionJar.flatMap { it.archiveFile })
    doLast {
        val archive = productionJar.get().archiveFile.get().asFile
        val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList() }
        listOf(
            "META-INF/neoforge.mods.toml",
            "ru/lazyhat/compukters/integration/create/CompuktersCreateMod.class",
            "ru/lazyhat/compukters/integration/create/CreateKineticsIntegration.class",
            "META-INF/compukters/addons/create-kinetics.cagb",
        ).forEach { required ->
            check(entries.count { it == required } == 1) { "$required is missing or duplicated in ${archive.name}" }
        }
        check(entries.none { it.startsWith("com/simibubi/create/") }) { "Create implementation classes leaked into ${archive.name}" }
        check(entries.none { it.startsWith("ru/lazyhat/compukters/impl/") }) { "base Compukters implementation leaked into ${archive.name}" }
    }
}

tasks.named("check") {
    dependsOn(verifyProductionJar)
}

tasks.named("assemble") {
    dependsOn(productionJar)
}

tasks.register("buildProductionJar") {
    group = "build"
    description = "Builds the standalone remapped Create addon mod JAR."
    dependsOn(productionJar, verifyProductionJar)
}
