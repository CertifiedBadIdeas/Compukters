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
    id("kotlin-convention")
}

val platformBuilder = configurations.create("addonGuestApiPlatformBuilder") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val compuktersPlatformBundle = configurations.create("addonGuestApiBasePlatform") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(platformBuilder.name, project(":compiler-k2-engine"))
    add(
        compuktersPlatformBundle.name,
        project(path = ":guest-platform", configuration = "compuktersPlatformBundle"),
    )
    add("implementation", project(":addon-guest-api"))
    add("implementation", project(":core"))
    add("implementation", project(":native-runtime-api"))
}

val guestApiSourceRoot = layout.projectDirectory.dir("src/guestApi/kotlin")
val guestApiDescriptor = layout.projectDirectory.file("src/guestApi/addon.api")
val guestApiAbiLock = layout.projectDirectory.file("src/guestApi/addon.lock")
val addonGuestApiBundleFile = layout.buildDirectory.file("addon-guest-api/${project.name}.cagb")
val generatedAddonHostRoot = layout.buildDirectory.dir("generated/sources/addonHost/kotlin")
val generatedAddonHostSource = generatedAddonHostRoot.map { it.file("GeneratedAddonHostContract.kt") }
val platformBundleFile = compuktersPlatformBundle.elements.map { files -> files.single().asFile }
val assembleAddonGuestApiBundle = tasks.register<JavaExec>("assembleAddonGuestApiBundle") {
    group = "build"
    description = "Builds this project's deterministic addon Guest API bundle."
    classpath = platformBuilder
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt"
    inputs.file(platformBundleFile)
    inputs.dir(guestApiSourceRoot)
    inputs.file(guestApiDescriptor)
    inputs.file(guestApiAbiLock)
    outputs.file(addonGuestApiBundleFile)
    outputs.file(generatedAddonHostSource)
    doFirst {
        args(
            "--platform-input",
            platformBundleFile.get().absolutePath,
            "--sources",
            guestApiSourceRoot.asFile.absolutePath,
            "--descriptor",
            guestApiDescriptor.asFile.absolutePath,
            "--abi-lock",
            guestApiAbiLock.asFile.absolutePath,
            "--output",
            addonGuestApiBundleFile.get().asFile.absolutePath,
            "--host-output",
            generatedAddonHostSource.get().asFile.absolutePath,
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedAddonHostRoot)
}

tasks.named("compileKotlin") {
    dependsOn(assembleAddonGuestApiBundle)
}

val updateAddonGuestApiAbiLock = tasks.register<JavaExec>("updateAddonGuestApiAbiLock") {
    group = "build setup"
    description = "Updates this project's checked-in addon Guest API ABI lock after an intentional API change."
    classpath = platformBuilder
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt"
    inputs.file(platformBundleFile)
    inputs.dir(guestApiSourceRoot)
    inputs.file(guestApiDescriptor)
    outputs.file(guestApiAbiLock)
    val validationBundle = layout.buildDirectory.file("addon-guest-api/${project.name}-abi-lock-update.cagb")
    outputs.file(validationBundle)
    doFirst {
        args(
            "--platform-input",
            platformBundleFile.get().absolutePath,
            "--sources",
            guestApiSourceRoot.asFile.absolutePath,
            "--descriptor",
            guestApiDescriptor.asFile.absolutePath,
            "--abi-lock",
            guestApiAbiLock.asFile.absolutePath,
            "--update-abi-lock",
            "true",
            "--output",
            validationBundle.get().asFile.absolutePath,
        )
    }
}

val addonGuestApiBundle = configurations.create("addonGuestApiBundle") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(addonGuestApiBundle.name, addonGuestApiBundleFile) {
        builtBy(assembleAddonGuestApiBundle)
        type = "cagb"
    }
}

tasks.named("check") {
    dependsOn(assembleAddonGuestApiBundle)
}
