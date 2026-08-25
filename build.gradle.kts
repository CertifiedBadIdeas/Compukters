/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
val compukterFfiRoot = rootProject.file("host/compukter-ffi")
val compukterFfiTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-ffi")
val compukterFfiLibrary = compukterFfiTargetRoot.resolve("release/${System.mapLibraryName("compukter_ffi")}")
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

val testCompukterFfiRust =
    tasks.register<Exec>("testCompukterFfiRust") {
        description = "Runs Compukter FFM adapter Rust tests."
        group = "verification"
        workingDir(compukterFfiRoot)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.file(compukterFfiRoot.resolve("Cargo.lock"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "test", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val testCompukterFfiRustRelease =
    tasks.register<Exec>("testCompukterFfiRustRelease") {
        description = "Runs optimized Compukter FFM adapter Rust tests."
        group = "verification"
        workingDir(compukterFfiRoot)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.file(compukterFfiRoot.resolve("Cargo.lock"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "test", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val fmtCompukterFfiRust =
    tasks.register<Exec>("fmtCompukterFfiRust") {
        description = "Checks Rust formatting for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterFfiRoot)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        commandLine("cargo", "fmt", "--check")
    }

val clippyCompukterFfiRust =
    tasks.register<Exec>("clippyCompukterFfiRust") {
        description = "Runs warning-free Clippy checks for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterFfiRoot)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.file(compukterFfiRoot.resolve("Cargo.lock"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        commandLine("cargo", "clippy", "--locked", "--offline", "--all-targets", "--", "-D", "warnings")
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val cargoBuildCompukterFfi =
    tasks.register<Exec>("cargoBuildCompukterFfi") {
        description = "Builds the release Compukter FFM platform library."
        group = "build"
        workingDir(compukterFfiRoot)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.file(compukterFfiRoot.resolve("Cargo.lock"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.dir(rootProject.file("host/compukter-vm/src"))
        outputs.file(compukterFfiLibrary)
        commandLine("cargo", "build", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
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
        environment("COMPUKTER_KOTLIN_SUBSET_ARTIFACT", artifact.get().asFile.absolutePath)
    }

val testKotlinSuspendCallVmConformance =
    tasks.register<Exec>("testKotlinSuspendCallVmConformance") {
        description = "Executes a K2-produced suspend project call with the pinned Compukter VM."
        group = "verification"
        dependsOn(":compiler-k2:generateSuspendCallConformanceArtifact")
        val harness = rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
        val artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/suspend-call.cpkt")
        val target = rootProject.file(".toolchain/build/cargo/compiler-k2-suspend-call-conformance")
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
        environment("COMPUKTER_KOTLIN_SUSPEND_CALL_ARTIFACT", artifact.get().asFile.absolutePath)
    }

val testKotlinWhenVmConformance =
    tasks.register<Exec>("testKotlinWhenVmConformance") {
        description = "Executes bounded K2 when branches with the pinned Compukter VM."
        group = "verification"
        dependsOn(":compiler-k2:generateWhenConformanceArtifact")
        val harness = rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
        val artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/when.cpkt")
        val target = rootProject.file(".toolchain/build/cargo/compiler-k2-when-conformance")
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
        environment("COMPUKTER_KOTLIN_WHEN_ARTIFACT", artifact.get().asFile.absolutePath)
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

val verifyLicensePolicy =
    tasks.register("verifyLicensePolicy") {
        description = "Rejects stale GPL identity and inconsistent Apache-2.0 metadata."
        group = "verification"
        val eligibleFiles =
            fileTree(rootDir) {
                include(
                    "*.gradle.kts",
                    "*.properties",
                    "README.md",
                    "AGENTS.md",
                    ".github/**/*.md",
                    "build-scripts/**/*.gradle.kts",
                    "build-scripts/**/*.kt",
                    "build-scripts/**/*.properties",
                    "config/**/*.properties",
                    "modules/**/*.gradle.kts",
                    "modules/**/*.kt",
                    "modules/**/*.rs",
                    "system/**/*.kt",
                    "host/**/*.rs",
                    "host/**/Cargo.toml",
                    "host/**/README.md",
                )
                exclude(
                    "**/build/**",
                    "**/target/**",
                    "**/.gradle/**",
                    "**/.gradle-sandbox/**",
                    "**/run/**",
                    "**/.agents/**",
                    "**/META-INF/licenses/**",
                    "tools/fonts/**",
                )
            }
        val canonicalLicense = rootProject.file("licenses/project/Apache-2.0.txt")
        val rootLicense = rootProject.file("LICENSE.md")
        val modProperties = rootProject.file("config/mod.properties")
        val ffiManifest = rootProject.file("host/compukter-ffi/Cargo.toml")
        val ffiLock = rootProject.file("host/compukter-ffi/Cargo.lock")
        val vmManifest = rootProject.file("host/compukter-vm/Cargo.toml")
        val componentInventory = rootProject.file("licenses/distribution-components.tsv")

        inputs.files(eligibleFiles)
        inputs.files(rootLicense, canonicalLicense, modProperties, ffiManifest, ffiLock, vmManifest, componentInventory)
        doLast {
            val forbidden = listOf("GNU General " + "Public License", "GPL-" + "3.0", "GPL" + "v3")
            val stale =
                eligibleFiles.files
                    .asSequence()
                    .filter(File::isFile)
                    .filter { file -> forbidden.any(file.readText()::contains) }
                    .map { it.relativeTo(rootDir).path }
                    .sorted()
                    .toList()
            check(stale.isEmpty()) { "stale GPL identity in active files: ${stale.joinToString()}" }
            check(canonicalLicense.isFile) { "canonical Apache-2.0 license is missing" }
            check(rootLicense.readBytes().contentEquals(canonicalLicense.readBytes())) {
                "LICENSE.md differs from the canonical Apache-2.0 text"
            }
            check("common_mod_license=Apache-2.0" in modProperties.readText()) {
                "mod metadata must use common_mod_license=Apache-2.0"
            }
            listOf(ffiManifest, vmManifest).forEach { manifest ->
                check("license = \"Apache-2.0\"" in manifest.readText()) {
                    "${manifest.relativeTo(rootDir)} must declare license = \"Apache-2.0\""
                }
            }
            val expectedRust =
                componentInventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "rust-native" }
                    .map { (_, component, version, _) -> component to version }
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            val actualRust =
                Regex("""(?ms)\[\[package]]\s+name = "([^"]+)"\s+version = "([^"]+)"""")
                    .findAll(ffiLock.readText())
                    .map { match -> match.groupValues[1] to match.groupValues[2] }
                    .filterNot { (component, _) -> component == "compukter-ffi" || component == "compukter-vm" }
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                    .toList()
            check(actualRust == expectedRust) {
                "native Rust dependency inventory mismatch: expected $expectedRust, found $actualRust"
            }
        }
    }

tasks.register("verifyLocalFast") {
    description = "Runs the standard local JVM and build-script verification slice."
    group = "verification"
    dependsOn(buildScriptsTest)
    dependsOn(verifyActiveMinecraftBaseline)
    dependsOn(verifyLicensePolicy)
    dependsOn(":core:test")
    dependsOn(":native-runtime:test")
    dependsOn(":playground:test")
    dependsOn(":v26_1-common:test")
    dependsOn(":v26_1-neoforge:test")
}

tasks.named("check") {
    dependsOn(verifyLicensePolicy)
}

tasks.register("verifyLocalFull") {
    description = "Runs local JVM tests and the managed Compukter VM tests."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn(testCompukterVmRust)
    dependsOn(testCompukterFfiRust)
    dependsOn(testCompukterFfiRustRelease)
    dependsOn(fmtCompukterFfiRust)
    dependsOn(clippyCompukterFfiRust)
    dependsOn(cargoBuildCompukterFfi)
    dependsOn(":native-runtime:nativeIntegrationTest")
    dependsOn(":core:programRuntimeIntegrationTest")
    dependsOn(":playground:endToEndTest")
    dependsOn(testCompilerArtifactVmConformance)
    dependsOn(testKotlinSubsetVmConformance)
    dependsOn(":v26_1-neoforge:runGameTestServer")
    dependsOn(":v26_1-neoforge:verifyPackagedCompukterFfi")
}
