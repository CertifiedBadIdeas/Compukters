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

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
    System.getProperty("ckl.low.image.golden.path")?.takeIf { it.isNotBlank() }?.let { path ->
        systemProperty("ckl.low.image.golden.path", path)
    }
}

val compukterJniLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-jni/release/${System.mapLibraryName("compukter_jni")}")
val terminalFixture = rootProject.file("host/compukter-vm/tests/fixtures/terminal-session.hex")

val nativeIntegrationTest =
    tasks.register<Test>("nativeIntegrationTest") {
        description = "Runs Kotlin-to-JNI-to-Rust Compukter VM integration tests."
        group = "verification"
        dependsOn(rootProject.tasks.named("cargoBuildCompukterJni"))
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
        inputs.file(compukterJniLibrary)
        inputs.file(terminalFixture)
        doFirst {
            systemProperty("compukter.jni.library", compukterJniLibrary.absolutePath)
            systemProperty("compukter.vm.terminalFixture", terminalFixture.absolutePath)
        }
    }

tasks.check {
    dependsOn(nativeIntegrationTest)
}
