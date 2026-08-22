/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.nativeRuntime)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

val compukterJniLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-jni/release/${System.mapLibraryName("compukter_jni")}")
val programRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt")

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
}

val programRuntimeIntegrationTest =
    tasks.register<Test>("programRuntimeIntegrationTest") {
        description = "Runs a compiler-produced Kotlin artifact through the server runtime host and native VM."
        group = "verification"
        dependsOn(
            ":compiler-k2:generateKotlinSubsetConformanceArtifact",
            rootProject.tasks.named("cargoBuildCompukterJni"),
        )
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
        inputs.file(compukterJniLibrary)
        inputs.file(programRuntimeArtifact)
        doFirst {
            systemProperty("compukters.jni.library", compukterJniLibrary.absolutePath)
            systemProperty("compukters.programRuntime.artifact", programRuntimeArtifact.get().asFile.absolutePath)
        }
    }

tasks.check {
    dependsOn(programRuntimeIntegrationTest)
}
