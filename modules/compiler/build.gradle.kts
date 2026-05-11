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

tasks.register<Test>("profileComputeVmBenchmark") {
    group = "verification"
    description = "Run CPU-only CKL workloads through the Kotlin bytecode VM and write a benchmark report."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    jvmArgs("-Xss64m")
    systemProperty(
        "ckl.benchmark.iterations",
        providers.gradleProperty("ckl.benchmark.iterations").orElse(System.getProperty("ckl.benchmark.iterations") ?: "10000").get(),
    )
    systemProperty(
        "ckl.benchmark.warmup.iterations",
        providers.gradleProperty("ckl.benchmark.warmup.iterations").orElse(System.getProperty("ckl.benchmark.warmup.iterations") ?: "1000").get(),
    )
    systemProperty(
        "ckl.benchmark.samples",
        providers.gradleProperty("ckl.benchmark.samples").orElse(System.getProperty("ckl.benchmark.samples") ?: "3").get(),
    )
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.lang.runtime.KotlinVmComputeBenchmarkProfileTest")
    }
    outputs.file(layout.buildDirectory.file("reports/profiling/kotlin-vm-compute-benchmark.tsv"))
    outputs.file(layout.buildDirectory.file("reports/profiling/kotlin-vm-compute-benchmark.md"))
}
