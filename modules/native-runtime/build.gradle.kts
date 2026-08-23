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
import java.util.Locale
import java.util.zip.ZipFile

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

val nativeOs =
    when {
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        else -> error("unsupported native build operating system: ${System.getProperty("os.name")}")
    }
val nativeArch =
    when (System.getProperty("os.arch").trim().lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("unsupported native build architecture: ${System.getProperty("os.arch")}")
    }
val nativeFilename =
    when (nativeOs) {
        "linux" -> "libcompukter_ffi.so"
        "windows" -> "compukter_ffi.dll"
        "macos" -> "libcompukter_ffi.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val compukterFfiLibrary = rootProject.file(".toolchain/build/cargo/compukter-ffi/release/$nativeFilename")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
val terminalFixture = rootProject.file("host/compukter-vm/tests/fixtures/terminal-session.hex")

val preparePackagedCompukterFfi =
    tasks.register<Sync>("preparePackagedCompukterFfi") {
        description = "Copies the current-host Compukter FFM library into its stable classpath resource."
        dependsOn(rootProject.tasks.named("cargoBuildCompukterFfi"))
        inputs.property("nativeOs", nativeOs)
        inputs.property("nativeArch", nativeArch)
        inputs.file(compukterFfiLibrary)
        into(generatedNativeResources)
        from(compukterFfiLibrary) {
            into("META-INF/natives/$nativeOs/$nativeArch")
        }
    }

sourceSets.main {
    resources.srcDir(generatedNativeResources)
}

tasks.processResources {
    dependsOn(preparePackagedCompukterFfi)
}

val nativeIntegrationTest =
    tasks.register<Test>("nativeIntegrationTest") {
        description = "Runs Kotlin-to-FFM-to-Rust Compukter VM integration tests."
        group = "verification"
        dependsOn(rootProject.tasks.named("cargoBuildCompukterFfi"))
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
        inputs.file(compukterFfiLibrary)
        inputs.file(terminalFixture)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        doFirst {
            systemProperty("compukter.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukter.vm.terminalFixture", terminalFixture.absolutePath)
        }
    }

val packagedNativeIntegrationTest =
    tasks.register<Test>("packagedNativeIntegrationTest") {
        description = "Extracts the packaged current-host FFM library and runs the terminal fixture in a fresh JVM."
        group = "verification"
        dependsOn(rootProject.tasks.named("cargoBuildCompukterFfi"))
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.PackagedFfmRuntimeIntegrationTest")
        inputs.file(terminalFixture)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        doFirst {
            systemProperty("compukter.vm.terminalFixture", terminalFixture.absolutePath)
        }
    }

val runtimeJar = tasks.named<Jar>("jar")
val verifyNativeRuntimeJarResource =
    tasks.register("verifyNativeRuntimeJarResource") {
        description = "Checks that native-runtime.jar contains exactly one current-host FFM resource."
        group = "verification"
        dependsOn(runtimeJar)
        inputs.file(runtimeJar.flatMap { it.archiveFile })
        inputs.property("nativeResourcePath", nativeResourcePath)
        doLast {
            val archive = runtimeJar.get().archiveFile.get().asFile
            val nativeEntries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .filter { it.startsWith("META-INF/natives/") }
                        .toList()
                }
            check(nativeEntries == listOf(nativeResourcePath)) {
                "expected only $nativeResourcePath in ${archive.name}, found $nativeEntries"
            }
        }
    }

tasks.check {
    dependsOn(nativeIntegrationTest, packagedNativeIntegrationTest, verifyNativeRuntimeJarResource)
}

tasks.register("verifyNativeRuntime") {
    description = "Runs JVM, explicit FFM, packaged FFM, and native resource verification."
    group = "verification"
    dependsOn(tasks.test, nativeIntegrationTest, packagedNativeIntegrationTest, verifyNativeRuntimeJarResource)
}
