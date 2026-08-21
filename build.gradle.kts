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
    base
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
}

val cleanWorkspaceSkipDirs =
    setOf(
        ".git",
        ".gradle",
        ".gradle-sandbox",
        ".idea",
        ".toolchain",
    )
val compukterVmBuildJobs =
    providers
        .gradleProperty("compukterVmBuildJobs")
        .orElse(Runtime.getRuntime().availableProcessors().toString())
        .get()
val compukterVmTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-vm")

fun cleanWorkspaceTargets(): List<File> {
    val repositoryRoot = rootProject.projectDir
    return repositoryRoot
        .walkTopDown()
        .onEnter { file ->
            file == repositoryRoot ||
                (file.name !in cleanWorkspaceSkipDirs && !file.resolve(".git").exists())
        }.filter { file ->
            file.isDirectory && (file.name == "build" || file.name == "target")
        }.distinctBy { it.canonicalFile }
        .toList()
}

val cleanWorkspace =
    tasks.register("cleanWorkspace") {
        description = "Deletes repo-local build and target outputs while preserving .toolchain."
        group = "build"

        doLast {
            cleanWorkspaceTargets().forEach { target ->
                if (target.exists()) {
                    delete(target)
                }
            }
        }
    }

tasks.named("clean") {
    dependsOn(cleanWorkspace)
}

val testCompukterVmRust =
    tasks.register<Exec>("testCompukterVmRust") {
        description = "Runs Compukter-VM submodule Rust tests."
        group = "verification"
        val vmRoot = rootProject.file("host/compukter-vm")
        val vmManifest = vmRoot.resolve("Cargo.toml")
        workingDir(vmRoot)
        inputs.file(vmManifest)
        inputs.file(vmRoot.resolve("Cargo.lock"))
        inputs.dir(vmRoot.resolve("src"))
        inputs.dir(vmRoot.resolve("tests"))
        inputs.property("compukterVmBuildJobs", compukterVmBuildJobs)
        doFirst {
            check(vmManifest.isFile) {
                "Compukter-VM submodule is not initialized; run: git submodule update --init --recursive"
            }
        }
        commandLine("cargo", "test", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterVmTargetRoot.absolutePath)
    }

val buildScriptsTest = gradle.includedBuild("build-scripts").task(":test")

tasks.register("verifyLocalFast") {
    description = "Runs the standard local JVM and build-script verification slice."
    group = "verification"
    dependsOn(buildScriptsTest)
    dependsOn(":core:test")
    dependsOn(":native-runtime:test")
    dependsOn(":v1_21_1-common:test")
    dependsOn(":v1_21_1-neoforge:test")
}

tasks.register("verifyLocalFull") {
    description = "Runs local JVM tests and the managed Compukter VM tests."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn(testCompukterVmRust)
}
