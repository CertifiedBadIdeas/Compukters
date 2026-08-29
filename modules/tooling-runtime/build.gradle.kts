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
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.workerClient)
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val compilerWorkerPayloadInput = configurations.create("compilerWorkerPayloadInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val analysisWorkerPayloadInput = configurations.create("analysisWorkerPayloadInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(compilerWorkerPayloadInput.name, project(path = ":compiler-k2", configuration = "compilerWorkerPayloadContent"))
    add(analysisWorkerPayloadInput.name, project(path = ":ide-analysis-k2", configuration = "analysisWorkerPayloadContent"))
}

application {
    mainClass = "ru.lazyhat.compukters.tooling.bundle.ToolingBundleMainKt"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val toolingBundleDirectory = layout.buildDirectory.dir("tooling-bundle/content")

val prepareToolingRuntimeBundle = tasks.register<JavaExec>("prepareToolingRuntimeBundle") {
    group = "build"
    description = "Assembles the shared compiler and analysis K2 runtime tree."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.files(compilerWorkerPayloadInput, analysisWorkerPayloadInput)
    outputs.dir(toolingBundleDirectory)
    doFirst {
        delete(toolingBundleDirectory)
        args(
            "assemble",
            compilerWorkerPayloadInput.singleFile.absolutePath,
            analysisWorkerPayloadInput.singleFile.absolutePath,
            toolingBundleDirectory.get().asFile.absolutePath,
        )
    }
}

val toolingRuntimeBundle = tasks.register<Zip>("toolingRuntimeBundle") {
    group = "distribution"
    description = "Packages the shared K2 tooling runtime."
    dependsOn(prepareToolingRuntimeBundle)
    from(toolingBundleDirectory)
    archiveFileName = "k2-tooling-workers.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyToolingRuntimeBundle = tasks.register<JavaExec>("verifyToolingRuntimeBundle") {
    group = "verification"
    description = "Reassembles, publishes, and verifies the shared K2 tooling runtime."
    dependsOn(tasks.classes, toolingRuntimeBundle)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.files(compilerWorkerPayloadInput, analysisWorkerPayloadInput)
    inputs.file(toolingRuntimeBundle.flatMap { it.archiveFile })
    val scratch = layout.buildDirectory.dir("tooling-bundle/verification")
    outputs.upToDateWhen { false }
    doFirst {
        args(
            "verify",
            compilerWorkerPayloadInput.singleFile.absolutePath,
            analysisWorkerPayloadInput.singleFile.absolutePath,
            toolingRuntimeBundle.get().archiveFile.get().asFile.absolutePath,
            scratch.get().asFile.absolutePath,
        )
    }
}

tasks.check {
    dependsOn(verifyToolingRuntimeBundle)
}
