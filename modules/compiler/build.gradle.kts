/*
 * The Compukter Kraft Developers
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

val rustVmNativePlatform = currentRustVmNativePlatform()
val rustVmReleaseNativeLibrary = rootProject.layout.projectDirectory.file("native/ckl-vm/target/release/${rustVmNativePlatform.libraryName}")
val rustVmCrateDir = rootProject.layout.projectDirectory.dir("native/ckl-vm")
val computeVmBenchmarkReports = layout.buildDirectory.dir("reports/profiling")
val computeVmBenchmarkTsv = computeVmBenchmarkReports.map { it.file("compute-vm-benchmark.tsv") }

tasks.register<Test>("profileComputeVmBenchmark") {
    group = "verification"
    description = "Run a CPU-only CKL VM benchmark against the same workload compiled as optimized Rust."
    dependsOn(":v1_21_1-neoforge:buildRustVmNativeLibraryRelease")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.lang.runtime.blazing.ComputeVmBenchmarkProfileTest")
    }
    systemProperty("ckl.vm.native.library", rustVmReleaseNativeLibrary.asFile.absolutePath)
    systemProperty("ckl.benchmark.rust.crate.dir", rustVmCrateDir.asFile.absolutePath)
    systemProperty("ckl.benchmark.python.command", System.getProperty("ckl.benchmark.python.command") ?: "python3")
    systemProperty("ckl.benchmark.compute.tsv.path", computeVmBenchmarkTsv.get().asFile.absolutePath)
    systemProperty("ckl.benchmark.iterations", System.getProperty("ckl.benchmark.iterations") ?: "500000")
    systemProperty("ckl.benchmark.warmup.iterations", System.getProperty("ckl.benchmark.warmup.iterations") ?: "50000")
    systemProperty("ckl.benchmark.samples", System.getProperty("ckl.benchmark.samples") ?: "5")
    doFirst {
        val timestamp =
            DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())
        val markdownPath = computeVmBenchmarkReports.get().file("compute-vm-benchmark-$timestamp.md")
        systemProperty("ckl.benchmark.compute.markdown.path", markdownPath.asFile.absolutePath)
    }
    outputs.file(computeVmBenchmarkTsv)
    outputs.dir(computeVmBenchmarkReports)
}

tasks.test {
    System.getProperty("ckl.image.golden.path")?.takeIf { it.isNotBlank() }?.let { path ->
        systemProperty("ckl.image.golden.path", path)
    }
    System.getProperty("ckl.image.backend.fixture.path")?.takeIf { it.isNotBlank() }?.let { path ->
        systemProperty("ckl.image.backend.fixture.path", path)
    }
    System.getProperty("ckl.low.image.golden.path")?.takeIf { it.isNotBlank() }?.let { path ->
        systemProperty("ckl.low.image.golden.path", path)
    }
}
