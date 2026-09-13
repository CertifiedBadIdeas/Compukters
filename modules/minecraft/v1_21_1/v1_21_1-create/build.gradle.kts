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
}

val platformBuilder = configurations.create("platformBuilder") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val compuktersPlatformBundle = configurations.create("compuktersPlatformBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(platformBuilder.name, projects.compilerK2Engine)
    add(compuktersPlatformBundle.name, project(path = projects.guestPlatform.path, configuration = "compuktersPlatformBundle"))
}

val guestApiSourceRoot = layout.projectDirectory.dir("src/guestApi/kotlin")
val guestApiDescriptor = layout.projectDirectory.file("src/guestApi/create-kinetics.api")
val createKineticsAddonBundle = layout.buildDirectory.file("addon-guest-api/create-kinetics.cagb")
val platformBundleFile = compuktersPlatformBundle.elements.map { files -> files.single().asFile }
val assembleCreateKineticsGuestApiBundle = tasks.register<JavaExec>("assembleCreateKineticsGuestApiBundle") {
    group = "build"
    description = "Builds the deterministic Create kinetics Guest API bundle."
    classpath = platformBuilder
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt"
    inputs.file(platformBundleFile)
    inputs.dir(guestApiSourceRoot)
    inputs.file(guestApiDescriptor)
    outputs.file(createKineticsAddonBundle)
    doFirst {
        args(
            "--platform-input",
            platformBundleFile.get().absolutePath,
            "--sources",
            guestApiSourceRoot.asFile.absolutePath,
            "--descriptor",
            guestApiDescriptor.asFile.absolutePath,
            "--output",
            createKineticsAddonBundle.get().asFile.absolutePath,
        )
    }
}

val createKineticsGuestApiBundle = configurations.create("createKineticsGuestApiBundle") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(createKineticsGuestApiBundle.name, createKineticsAddonBundle) {
        builtBy(assembleCreateKineticsGuestApiBundle)
        type = "cagb"
    }
}

tasks.processResources {
    dependsOn(assembleCreateKineticsGuestApiBundle)
    from(createKineticsAddonBundle) {
        into("META-INF/compukters/addons")
    }
}

tasks.check {
    dependsOn(assembleCreateKineticsGuestApiBundle)
}
