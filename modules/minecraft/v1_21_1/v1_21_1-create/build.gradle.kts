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

plugins {
    id("addon-guest-api-convention")
    alias(libs.plugins.v1211)
    alias(libs.plugins.commonConvention)
}

architectury {
    common("neoforge")
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
    implementation(projects.addonGuestApi)
    implementation(projects.v1211Common)
    modImplementation(libs.create.v1211)
    testImplementation(projects.compilerArtifact)
    testImplementation(projects.compilerClient)
    testImplementation(projects.compilerK2)
    testImplementation(projects.platformBundle)
    testImplementation(kotlin("test"))
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
