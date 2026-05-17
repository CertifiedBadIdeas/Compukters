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

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

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
    testImplementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    modImplementation(libs.geckolib.neoforge.v1211)

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v1211Common.path, configuration = "namedElements"))
}

val rustVmNativePlatform = currentRustVmNativePlatform()
val rustVmNativeLibrary = rootProject.layout.projectDirectory.file("native/rux-vm/target/debug/${rustVmNativePlatform.libraryName}")
val runtimeVmProfilingReports = layout.buildDirectory.dir("reports/profiling")
val runtimeVmImageProfile = runtimeVmProfilingReports.map { it.file("runtime-vm-image.tsv") }
val runtimeVmProfileRuns = runtimeVmProfilingReports.map { it.dir("runs") }
val runtimeVmComparisonReport = runtimeVmProfilingReports.map { it.file("runtime-vm-comparison.md") }

fun runtimeProfileTimestamp(): String =
    OffsetDateTime
        .now()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssXXX"))
        .replace(':', '-')

fun Test.configureRuntimeVmProfilingTestTask() {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

tasks.register<Test>("profileRuntimeVmImage") {
    configureRuntimeVmProfilingTestTask()
    description = "Run runtime profiling workloads with the Rust Rux image VM runner and write raw profiling data."
    dependsOn("buildRustVmNativeLibrary")
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeVmProfilingReportTest")
    }
    systemProperty("rux.vm.native.library", rustVmNativeLibrary.asFile.absolutePath)
    systemProperty("ckl.profiling.runtime.name", "Rust image")
    systemProperty("ckl.profiling.profile.path", runtimeVmImageProfile.get().asFile.absolutePath)
    systemProperty("ckl.profiling.runs.dir", runtimeVmProfileRuns.get().asFile.absolutePath)
    doFirst {
        systemProperty("ckl.profiling.run.timestamp", runtimeProfileTimestamp())
    }
    outputs.file(runtimeVmImageProfile)
}

tasks.register<Test>("profileRuntimeVmComparison") {
    configureRuntimeVmProfilingTestTask()
    description = "Run runtime profiling workloads and write a Markdown comparison report over all archived runs."
    dependsOn("profileRuntimeVmImage")
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeVmProfilingReportAggregationTest")
    }
    systemProperty("ckl.profiling.runs.dir", runtimeVmProfileRuns.get().asFile.absolutePath)
    systemProperty("ckl.profiling.comparison.path", runtimeVmComparisonReport.get().asFile.absolutePath)
    outputs.file(runtimeVmComparisonReport)
}
