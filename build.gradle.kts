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
val compukterJniRoot = rootProject.file("host/compukter-jni")
val compukterJniTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-jni")
val compukterJniLibrary = compukterJniTargetRoot.resolve("release/${System.mapLibraryName("compukter_jni")}")
val compukterVmManifest = rootProject.file("host/compukter-vm/Cargo.toml")
val compukterVmLock = rootProject.file("host/compukter-vm/Cargo.lock")

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

val testCompukterJniRust =
    tasks.register<Exec>("testCompukterJniRust") {
        description = "Runs Compukter JNI adapter Rust tests."
        group = "verification"
        workingDir(compukterJniRoot)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.file(compukterJniRoot.resolve("Cargo.lock"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "test", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val testCompukterJniRustRelease =
    tasks.register<Exec>("testCompukterJniRustRelease") {
        description = "Runs optimized Compukter JNI adapter Rust tests."
        group = "verification"
        workingDir(compukterJniRoot)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.file(compukterJniRoot.resolve("Cargo.lock"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "test", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val fmtCompukterJniRust =
    tasks.register<Exec>("fmtCompukterJniRust") {
        description = "Checks Rust formatting for the Compukter JNI adapter."
        group = "verification"
        workingDir(compukterJniRoot)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.dir(compukterJniRoot.resolve("src"))
        commandLine("cargo", "fmt", "--check")
    }

val clippyCompukterJniRust =
    tasks.register<Exec>("clippyCompukterJniRust") {
        description = "Runs warning-free Clippy checks for the Compukter JNI adapter."
        group = "verification"
        workingDir(compukterJniRoot)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.file(compukterJniRoot.resolve("Cargo.lock"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "clippy", "--locked", "--offline", "--all-targets", "--", "-D", "warnings")
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val cargoBuildCompukterJni =
    tasks.register<Exec>("cargoBuildCompukterJni") {
        description = "Builds the release Compukter JNI platform library."
        group = "build"
        workingDir(compukterJniRoot)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.file(compukterJniRoot.resolve("Cargo.lock"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        outputs.file(compukterJniLibrary)
        commandLine("cargo", "build", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val testCompilerArtifactVmConformance =
    tasks.register<Exec>("testCompilerArtifactVmConformance") {
        description = "Verifies Kotlin executable Artifact v1 output with the pinned Compukter VM."
        group = "verification"
        dependsOn(":compiler-artifact:test")
        val harness = rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
        val artifact = project(":compiler-artifact").layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt")
        val target = rootProject.file(".toolchain/build/cargo/compiler-artifact-conformance")
        inputs.file(harness)
        inputs.file(rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.lock"))
        inputs.file(rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs"))
        inputs.file(artifact)
        doFirst {
            check(harness.isFile) { "compiler artifact Rust conformance harness is missing" }
        }
        commandLine("cargo", "test", "--locked", "--offline", "--manifest-path", harness.absolutePath)
        environment("CARGO_TARGET_DIR", target.absolutePath)
        environment("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT", artifact.get().asFile.absolutePath)
    }

val testKotlinSubsetVmConformance =
    tasks.register<Exec>("testKotlinSubsetVmConformance") {
        description = "Verifies K2-lowered Kotlin subset output with the pinned Compukter VM."
        group = "verification"
        dependsOn(":compiler-k2:generateKotlinSubsetConformanceArtifact")
        val harness = rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
        val artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt")
        val target = rootProject.file(".toolchain/build/cargo/compiler-k2-conformance")
        inputs.file(harness)
        inputs.file(rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.lock"))
        inputs.file(rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs"))
        inputs.file(artifact)
        doFirst {
            check(harness.isFile) { "compiler artifact Rust conformance harness is missing" }
        }
        commandLine("cargo", "test", "--locked", "--offline", "--manifest-path", harness.absolutePath)
        environment("CARGO_TARGET_DIR", target.absolutePath)
        environment("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT", artifact.get().asFile.absolutePath)
    }

val buildScriptsTest = gradle.includedBuild("build-scripts").task(":test")

val verifyActiveMinecraftBaseline =
    tasks.register("verifyActiveMinecraftBaseline") {
        group = "verification"
        description = "Rejects stale Minecraft and JDK baseline configuration."
        val activeFiles =
            fileTree(rootDir) {
                include(
                    "README.md",
                    "AGENTS.md",
                    "*.gradle.kts",
                    "*.properties",
                    "docs/**/*.md",
                    "gradle/*.toml",
                    "build-scripts/**/*.gradle.kts",
                    "build-scripts/**/*.kt",
                    "build-scripts/**/*.properties",
                    "config/**/*.properties",
                    "modules/**/*.gradle.kts",
                    "modules/**/*.json",
                    "modules/**/*.kt",
                    "modules/**/*.properties",
                    "modules/**/*.toml",
                )
                exclude(
                    "**/build/**",
                    "**/.gradle/**",
                    "docs/superpowers/specs/**",
                    "docs/superpowers/plans/**",
                )
            }
        val forbiddenTokens =
            listOf(
                "v1" + "_21_1",
                "1.21." + "1",
                "Parch" + "ment",
                "Java " + "17",
                "Java " + "21",
                "JDK " + "17",
                "JDK " + "21",
                "JVM " + "17",
                "JVM " + "21",
                "architectury-" + "neoforge",
                "Remap" + "JarTask",
            )

        inputs.files(activeFiles)
        doLast {
            check(rootProject.file("modules/v26_1/v26_1-common").isDirectory) {
                "active common module modules/v26_1/v26_1-common is missing"
            }
            check(rootProject.file("modules/v26_1/v26_1-neoforge").isDirectory) {
                "active NeoForge module modules/v26_1/v26_1-neoforge is missing"
            }
            val matches =
                activeFiles.files
                    .asSequence()
                    .filter(File::isFile)
                    .filter { file -> forbiddenTokens.any(file.readText()::contains) }
                    .map { it.relativeTo(rootDir).path }
                    .sorted()
                    .toList()
            check(matches.isEmpty()) {
                "stale Minecraft/JDK baseline references: ${matches.joinToString()}"
            }
        }
    }

tasks.register("verifyLocalFast") {
    description = "Runs the standard local JVM and build-script verification slice."
    group = "verification"
    dependsOn(buildScriptsTest)
    dependsOn(verifyActiveMinecraftBaseline)
    dependsOn(":core:test")
    dependsOn(":native-runtime:test")
    dependsOn(":playground:test")
    dependsOn(":v26_1-common:test")
    dependsOn(":v26_1-neoforge:test")
}

tasks.register("verifyLocalFull") {
    description = "Runs local JVM tests and the managed Compukter VM tests."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn(testCompukterVmRust)
    dependsOn(testCompukterJniRust)
    dependsOn(testCompukterJniRustRelease)
    dependsOn(fmtCompukterJniRust)
    dependsOn(clippyCompukterJniRust)
    dependsOn(cargoBuildCompukterJni)
    dependsOn(":native-runtime:nativeIntegrationTest")
    dependsOn(":core:programRuntimeIntegrationTest")
    dependsOn(":playground:endToEndTest")
    dependsOn(testCompilerArtifactVmConformance)
    dependsOn(testKotlinSubsetVmConformance)
    dependsOn(":v26_1-neoforge:runGameTestServer")
    dependsOn(":v26_1-neoforge:verifyPackagedCompukterJni")
}
