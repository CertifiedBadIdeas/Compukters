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
val compukterJniLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-jni/release/${System.mapLibraryName("compukter_jni")}")

tasks.named<JavaExec>("run") {
    dependsOn(":compiler-k2:prepareCompilerWorkerPayload", rootProject.tasks.named("cargoBuildCompukterJni"))
    standardInput = System.`in`
    doFirst {
        systemProperty("compukters.worker.payload", workerPayload.get().asFile.absolutePath)
        systemProperty("compukters.jni.library", compukterJniLibrary.absolutePath)
    }
}

tasks.check {
    dependsOn(tasks.named("assertNoK2CompilerRuntime"))
}
