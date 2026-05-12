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
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
}

tasks.register("profileRuntimeVmComparison") {
    group = "verification"
    description = "Run runtime profiling workloads and write a Markdown comparison report over all archived runs."
    dependsOn(":v1_21_1-neoforge:profileRuntimeVmComparison")
}

tasks.register("profileComputeVmBenchmark") {
    group = "verification"
    description = "Run a CPU-only CKL VM benchmark with the release Rust CKL VM JNI library."
    dependsOn(":compiler:profileComputeVmBenchmark")
}

tasks.register("profileComputeVmBenchmarkDebug") {
    group = "verification"
    description = "Run a CPU-only CKL VM benchmark with the debug Rust CKL VM JNI library."
    dependsOn(":compiler:profileComputeVmBenchmarkDebug")
}

tasks.register("profileComputeVmBenchmarkRelease") {
    group = "verification"
    description = "Run a CPU-only CKL VM benchmark with the release Rust CKL VM JNI library."
    dependsOn(":compiler:profileComputeVmBenchmarkRelease")
}

tasks.register("profileComputeVmBenchmarkComparison") {
    group = "verification"
    description = "Run CPU-only CKL VM benchmarks for both debug and release Rust CKL VM JNI libraries."
    dependsOn(":compiler:profileComputeVmBenchmarkComparison")
}
