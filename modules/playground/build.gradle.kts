/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compilerClient)
    implementation(projects.nativeRuntime)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ru.lazyhat.compukters.playground.PlaygroundMainKt"
}

val workerPayload = project(":compiler-k2").layout.buildDirectory.dir("worker-payload/content")
val compukterFfiLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
val repositoryRoot = rootProject.layout.projectDirectory

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.playground.integration.*")
}

tasks.named<JavaExec>("run") {
    dependsOn(":compiler-k2:prepareCompilerWorkerPayload", rootProject.tasks.named("cargoBuildCompukterFfi"))
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    doFirst {
        systemProperty("compukters.worker.payload", workerPayload.get().asFile.absolutePath)
        systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
    }
}

val endToEndTest =
    tasks.register<Test>("endToEndTest") {
        description = "Compiles and executes the standalone Kotlin example through the real worker, FFM, and Rust VM."
        group = "verification"
        dependsOn(":compiler-k2:prepareCompilerWorkerPayload", rootProject.tasks.named("cargoBuildCompukterFfi"))
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.playground.integration.*")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        inputs.dir(workerPayload)
        inputs.file(compukterFfiLibrary)
        inputs.dir(repositoryRoot.dir("examples/hello"))
        doFirst {
            systemProperty("compukters.worker.payload", workerPayload.get().asFile.absolutePath)
            systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukters.project.root", repositoryRoot.asFile.absolutePath)
        }
    }

tasks.check {
    dependsOn(tasks.named("assertNoK2CompilerRuntime"))
    dependsOn(endToEndTest)
}
