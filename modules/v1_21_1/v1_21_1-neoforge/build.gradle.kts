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

@file:Suppress("PropertyName")

plugins {
    idea
    alias(libs.plugins.v1211)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.metadataConvention)
}

val gameTest by sourceSets.creating

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

tasks.configureEach {
    if (name == "runGameTestServer") {
        dependsOn(gameTest.classesTaskName)
    }
}

loom {
    // Generic client / client2 / server runs are declared in the
    // `loom-runs-convention` precompiled script plugin (build-scripts).
    // Only neoforge-specific runs live here.
    runs {
        register("gameTestServer") {
            server()
            runDir("run/gameTestServer")
            property("neoforge.enableGameTest", "true")
            property("neoforge.enabledGameTestNamespaces", "compukterkraft,minecraft")
            property("neoforge.gameTestServer", "true")
            property("kotlinx.coroutines.debug", "off")
            ideConfigGenerated(true)
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v1211Common.path))
            sourceSet("main", project(projects.core.path))
            sourceSet("main", project(":compiler"))
            sourceSet(gameTest.name)
        }
    }
}

dependencies {
    common(project(path = projects.v1211Common.path, configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = projects.v1211Common.path, configuration = "transformProductionNeoForge"))

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v1211Common.path, configuration = "namedElements"))
}

val rustVmNativeLibrary = rootProject.layout.projectDirectory.file("native/ckl-vm/target/debug/libckl_vm.so")
val runtimeVmProfilingReports = layout.buildDirectory.dir("reports/profiling")
val runtimeVmJvmProfile = runtimeVmProfilingReports.map { it.file("runtime-vm-jvm.tsv") }
val runtimeVmRustProfile = runtimeVmProfilingReports.map { it.file("runtime-vm-rust.tsv") }
val runtimeVmComparisonReport = runtimeVmProfilingReports.map { it.file("runtime-vm-comparison.md") }

fun Test.configureRuntimeVmProfilingTestTask() {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

val profileRuntimeVmJvm =
    tasks.register<Test>("profileRuntimeVmJvm") {
        configureRuntimeVmProfilingTestTask()
        description = "Run runtime profiling workloads with the JVM CKL VM runner and write raw profiling data."
        filter {
            includeTestsMatching("ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeVmProfilingReportTest")
        }
        systemProperty("ckl.vm.runner", "kotlin")
        systemProperty("ckl.profiling.runner.name", "JVM")
        systemProperty("ckl.profiling.profile.path", runtimeVmJvmProfile.get().asFile.absolutePath)
        outputs.file(runtimeVmJvmProfile)
    }

val profileRuntimeVmRust =
    tasks.register<Test>("profileRuntimeVmRust") {
        configureRuntimeVmProfilingTestTask()
        description = "Run runtime profiling workloads with the Rust CKL VM runner and write raw profiling data."
        dependsOn("buildRustVmNativeLibrary")
        mustRunAfter(profileRuntimeVmJvm)
        filter {
            includeTestsMatching("ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeVmProfilingReportTest")
        }
        systemProperty("ckl.vm.runner", "rust")
        systemProperty("ckl.vm.native.library", rustVmNativeLibrary.asFile.absolutePath)
        systemProperty("ckl.profiling.runner.name", "Rust")
        systemProperty("ckl.profiling.profile.path", runtimeVmRustProfile.get().asFile.absolutePath)
        outputs.file(runtimeVmRustProfile)
    }

tasks.register<Test>("profileRuntimeVmComparison") {
    configureRuntimeVmProfilingTestTask()
    description = "Run isolated JVM and Rust VM runtime profiling tasks and write a Markdown comparison report."
    dependsOn(profileRuntimeVmJvm, profileRuntimeVmRust)
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeVmProfilingReportAggregationTest")
    }
    systemProperty("ckl.profiling.jvm.profile.path", runtimeVmJvmProfile.get().asFile.absolutePath)
    systemProperty("ckl.profiling.rust.profile.path", runtimeVmRustProfile.get().asFile.absolutePath)
    systemProperty("ckl.profiling.report.path", runtimeVmComparisonReport.get().asFile.absolutePath)
    outputs.file(runtimeVmComparisonReport)
}
