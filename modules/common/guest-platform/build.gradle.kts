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
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    testImplementation(kotlin("test"))
}

val platformBuilder = configurations.create("platformBuilder") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(platformBuilder.name, projects.compilerK2Engine)
}

val platformSourceRoot = layout.projectDirectory.dir("src/platform")
val platformDescriptor = platformSourceRoot.file("modules.toml")
val addonDescriptor = layout.projectDirectory.file("src/addons/create-kinetics.api")
val fullPlatformBundle = layout.buildDirectory.file("platform/compukters-platform-full.cpb")
val platformBundle = layout.buildDirectory.file("platform/compukters-platform.cpb")
val createKineticsAddonBundle = layout.buildDirectory.file("platform/create-kinetics.cagb")
val assembleFullPlatformBundle = tasks.register<JavaExec>("assembleFullPlatformBundle") {
    group = "build"
    description = "Builds the complete intermediate Compukters platform bundle."
    classpath = platformBuilder
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.PlatformBundleBuilderMainKt"
    inputs.dir(platformSourceRoot)
    inputs.file(platformDescriptor)
    inputs.file(addonDescriptor)
    outputs.file(fullPlatformBundle)
    args(
        "--sources",
        platformSourceRoot.asFile.absolutePath,
        "--descriptor",
        platformDescriptor.asFile.absolutePath,
        "--output",
        fullPlatformBundle.get().asFile.absolutePath,
        "--addon-descriptor",
        addonDescriptor.asFile.absolutePath,
    )
}
val assemblePlatformBundle = tasks.register<JavaExec>("assemblePlatformBundle") {
    group = "build"
    description = "Builds the base platform and extracts deterministic addon Guest API bundles."
    dependsOn(assembleFullPlatformBundle)
    classpath = platformBuilder
    mainClass = "ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiSplitterMainKt"
    inputs.file(fullPlatformBundle)
    inputs.file(addonDescriptor)
    outputs.files(platformBundle, createKineticsAddonBundle)
    args(
        "--input",
        fullPlatformBundle.get().asFile.absolutePath,
        "--descriptor",
        addonDescriptor.asFile.absolutePath,
        "--platform-output",
        platformBundle.get().asFile.absolutePath,
        "--addon-output",
        createKineticsAddonBundle.get().asFile.absolutePath,
    )
}

val compuktersPlatformBundle = configurations.create("compuktersPlatformBundle") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val createKineticsGuestApiBundle = configurations.create("createKineticsGuestApiBundle") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(compuktersPlatformBundle.name, platformBundle) {
        builtBy(assemblePlatformBundle)
        type = "cpb"
    }
    add(createKineticsGuestApiBundle.name, createKineticsAddonBundle) {
        builtBy(assemblePlatformBundle)
        type = "cagb"
    }
}

tasks.processResources {
    from("src/platform") {
        into("compukters-platform/sources")
        exclude("libraries/create-kinetics/**")
    }
}

tasks.test {
    dependsOn(tasks.jar)
    doFirst {
        systemProperty("compukters.platform.source-root", layout.projectDirectory.dir("src/platform").asFile.absolutePath)
        systemProperty("compukters.platform.source-archive", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}

tasks.check {
    dependsOn(assemblePlatformBundle)
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
