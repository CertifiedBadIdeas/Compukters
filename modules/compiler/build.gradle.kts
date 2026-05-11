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
val computeVmBenchmarkMarkdown = computeVmBenchmarkReports.map { it.file("compute-vm-benchmark.md") }

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
    systemProperty("ckl.benchmark.compute.tsv.path", computeVmBenchmarkTsv.get().asFile.absolutePath)
    systemProperty("ckl.benchmark.compute.markdown.path", computeVmBenchmarkMarkdown.get().asFile.absolutePath)
    systemProperty("ckl.benchmark.iterations", System.getProperty("ckl.benchmark.iterations") ?: "500000")
    systemProperty("ckl.benchmark.warmup.iterations", System.getProperty("ckl.benchmark.warmup.iterations") ?: "50000")
    systemProperty("ckl.benchmark.samples", System.getProperty("ckl.benchmark.samples") ?: "5")
    outputs.file(computeVmBenchmarkTsv)
    outputs.file(computeVmBenchmarkMarkdown)
}
