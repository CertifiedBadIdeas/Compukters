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
    id("media-license-convention")
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
    id("addon-sdk-distribution")
}

organizeCompuktersTasks()

listOf(false, true).forEach { release ->
    tasks.register<Sync>(if (release) "stageReleaseDistributionModJars" else "stageDistributionModJars") {
        description = "Stages verified ${if (release) "universal release" else "local production"} mod JARs for distribution."
        outputs.upToDateWhen { false }
        into(layout.buildDirectory.dir("distribution"))
        listOf("v1_21_1" to "1.21.1", "v26_1" to "26.1.2").forEach { (target, minecraft) ->
            val modProject = project(":$target-neoforge")
            dependsOn("${modProject.path}:${if (release) "buildReleaseUniversalJar" else "buildProductionUniversalJar"}")
            val archiveTask = if (target == "v1_21_1") "remapJar" else "shadowJar"
            from(providers.provider {
                modProject.tasks.named<AbstractArchiveTask>(archiveTask).get().archiveFile.get()
            }) { into(minecraft) }
        }
    }
}

val compukterVmBuildJobs =
    providers
        .gradleProperty("compukterVmBuildJobs")
        .orElse(Runtime.getRuntime().availableProcessors().toString())
        .get()
val compukterVmTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-vm")
val compukterVmRoot = rootProject.file("host/compukter-vm")
val compukterFfiRoot = compukterVmRoot.resolve("ffi")
val compukterFfiTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-ffi")
val compukterFfiLibrary = compukterFfiTargetRoot.resolve("release/${System.mapLibraryName("compukter_ffi")}")
val compukterJniRoot = compukterVmRoot.resolve("jni")
val compukterJniTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-jni")
val compukterJniLibrary = compukterJniTargetRoot.resolve("release/${System.mapLibraryName("compukter_jni")}")
val compukterVmManifest = compukterVmRoot.resolve("Cargo.toml")
val compukterVmLock = compukterVmRoot.resolve("Cargo.lock")

registerCompukterVmReleaseTasks(compukterVmRoot)

val releaseRuntimeBundleDirectory = providers.gradleProperty("compukterRuntimeBundleDir").map(rootProject::file)
val releaseRuntimeContract = runtimeBundleContractProvider()
val downloadedReleaseRuntimeBundleDirectory =
    providers.provider {
        gradle.gradleUserHomeDir.resolve("caches/compukters/runtime/${releaseRuntimeContract.get().runtimeVersion}")
    }

tasks.register("downloadCompukterRuntimeBundles") {
    description = "Downloads the pinned Linux and Windows dual-transport Runtime release assets into the Gradle cache."
    group = "build"
    inputs.property("runtimeVersion", releaseRuntimeContract.map(RuntimeBundleContract::runtimeVersion))
    inputs.property("runtimeReleaseTag", releaseRuntimeContract.map(RuntimeBundleContract::releaseTag))
    inputs.property("runtimeVmCommit", releaseRuntimeContract.map(RuntimeBundleContract::vmCommit))
    inputs.property("runtimeAbi", releaseRuntimeContract.map(RuntimeBundleContract::ffiAbi))
    outputs.dir(downloadedReleaseRuntimeBundleDirectory)
    onlyIf { !releaseRuntimeBundleDirectory.isPresent }
    doLast {
        val contract = releaseRuntimeContract.get()
        val result =
            RuntimeBundleDownloadSupport.download(
                downloadedReleaseRuntimeBundleDirectory.get().toPath(),
                contract,
            )
        println("Runtime ${contract.runtimeVersion}: ${result.name.lowercase()} in ${downloadedReleaseRuntimeBundleDirectory.get()}")
    }
}

val cleanWorkspace =
    tasks.register("cleanWorkspace") {
        description = "Deletes repo-local build and target outputs while preserving .toolchain."
        group = "build"

        doLast {
            workspaceCleanTargets(rootProject.projectDir.toPath()).forEach { target ->
                if (target.toFile().exists()) {
                    delete(target.toFile())
                }
            }
        }
    }

tasks.named<Delete>("clean") {
    dependsOn(cleanWorkspace)
    delete(layout.projectDirectory.dir("dist"))
}

val testCompukterVmRust =
    tasks.register<Exec>("testCompukterVmRust") {
        description = "Runs Compukter-VM submodule Rust tests."
        group = "verification"
        val vmManifest = compukterVmRoot.resolve("Cargo.toml")
        workingDir(compukterVmRoot)
        inputs.file(vmManifest)
        inputs.file(compukterVmRoot.resolve("Cargo.lock"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
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
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "test", "-p", "compukter-ffi", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val testCompukterFfiRustRelease =
    tasks.register<Exec>("testCompukterFfiRustRelease") {
        description = "Runs optimized Compukter FFM adapter Rust tests."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "test", "-p", "compukter-ffi", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val fmtCompukterFfiRust =
    tasks.register<Exec>("fmtCompukterFfiRust") {
        description = "Checks Rust formatting for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        commandLine("cargo", "fmt", "--package", "compukter-ffi", "--", "--check")
    }

val clippyCompukterFfiRust =
    tasks.register<Exec>("clippyCompukterFfiRust") {
        description = "Runs warning-free Clippy checks for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "clippy", "-p", "compukter-ffi", "--locked", "--offline", "--all-targets", "--", "-D", "warnings")
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val cargoBuildCompukterFfi =
    tasks.register<Exec>("cargoBuildCompukterFfi") {
        description = "Builds the release Compukter FFM platform library."
        group = "build"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("src"))
        outputs.file(compukterFfiLibrary)
        commandLine("cargo", "build", "-p", "compukter-ffi", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val testCompukterJniRust =
    tasks.register<Exec>("testCompukterJniRust") {
        description = "Compiles and tests the Compukter JNI adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("src"))
        commandLine("cargo", "test", "-p", "compukter-jni", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val fmtCompukterJniRust =
    tasks.register<Exec>("fmtCompukterJniRust") {
        description = "Checks Rust formatting for the Compukter JNI adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.dir(compukterJniRoot.resolve("src"))
        commandLine("cargo", "fmt", "--package", "compukter-jni", "--", "--check")
    }

val clippyCompukterJniRust =
    tasks.register<Exec>("clippyCompukterJniRust") {
        description = "Runs warning-free Clippy checks for the Compukter JNI adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("src"))
        commandLine("cargo", "clippy", "-p", "compukter-jni", "--locked", "--offline", "--all-targets", "--", "-D", "warnings")
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val cargoBuildCompukterJni =
    tasks.register<Exec>("cargoBuildCompukterJni") {
        description = "Builds the release Compukter JNI platform library."
        group = "build"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterJniRoot.resolve("Cargo.toml"))
        inputs.dir(compukterJniRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("src"))
        outputs.file(compukterJniLibrary)
        commandLine("cargo", "build", "-p", "compukter-jni", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterJniTargetRoot.absolutePath)
    }

val verifyKotlinVmConformance =
    tasks.register("verifyKotlinVmConformance") {
        description = "Runs every registered Kotlin-to-Compukter-VM execution-conformance scenario."
        group = "verification"
    }

val compilerArtifactVmConformanceHarness =
    rootProject.file("modules/common/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
val compilerArtifactVmConformanceLock =
    rootProject.file("modules/common/compiler-artifact/src/test/rust/executable-conformance/Cargo.lock")
val compilerArtifactVmConformanceSource =
    rootProject.file("modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs")

fun registerKotlinVmConformance(
    taskName: String,
    taskDescription: String,
    artifactTask: String,
    artifact: Provider<RegularFile>,
    cargoTargetDirectory: String,
    artifactEnvironmentVariable: String,
    conformanceScenario: String,
    additionalArtifacts: Map<String, Provider<RegularFile>> = emptyMap(),
) {
    val conformanceTask =
        tasks.register<Exec>(taskName) {
            description = taskDescription
            group = "verification"
            dependsOn(artifactTask)
            inputs.file(compilerArtifactVmConformanceHarness)
            inputs.file(compilerArtifactVmConformanceLock)
            inputs.file(compilerArtifactVmConformanceSource)
            inputs.file(artifact)
            inputs.files(additionalArtifacts.values)
            doFirst {
                check(compilerArtifactVmConformanceHarness.isFile) {
                    "compiler artifact Rust conformance harness is missing"
                }
                check(artifact.get().asFile.isFile) {
                    "$taskName requires generated artifact ${artifact.get().asFile}"
                }
                additionalArtifacts.forEach { (_, path) ->
                    check(path.get().asFile.isFile) { "$taskName requires generated artifact ${path.get().asFile}" }
                }
            }
            commandLine(
                "cargo",
                "test",
                "--locked",
                "--offline",
                "--manifest-path",
                compilerArtifactVmConformanceHarness.absolutePath,
                "--test",
                "kotlin_writer",
                "--",
                conformanceScenario,
            )
            environment("CARGO_TARGET_DIR", rootProject.file(cargoTargetDirectory).absolutePath)
            environment(artifactEnvironmentVariable, artifact.get().asFile.absolutePath)
            additionalArtifacts.forEach { (name, path) -> environment(name, path.get().asFile.absolutePath) }
        }
    verifyKotlinVmConformance.configure {
        dependsOn(conformanceTask)
    }
}

registerKotlinVmConformance(
    taskName = "testKotlinMathVmConformance",
    taskDescription = "Executes portable Guest math, numerical edge cases and callable references.",
    artifactTask = ":compiler-k2:generateMathConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-math.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-math-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MATH_ARTIFACT",
    conformanceScenario = "math",
)

registerKotlinVmConformance(
    taskName = "testKotlinEqualsVmConformance",
    taskDescription = "Executes uniform virtual equals and generated data-class equality.",
    artifactTask = ":compiler-k2:generateEqualsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/equals.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-equals-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_EQUALS_ARTIFACT",
    conformanceScenario = "equals",
)

registerKotlinVmConformance(
    taskName = "testKotlinHashCodeVmConformance",
    taskDescription = "Executes virtual hashCode, scalar boxes and equality-compatible hashes.",
    artifactTask = ":compiler-k2:generateHashCodeConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/hash-code.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-hash-code-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_HASH_CODE_ARTIFACT",
    conformanceScenario = "hash-code",
    additionalArtifacts = mapOf("COMPUKTER_KOTLIN_HASH_CODE_ARRAY_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/hash-code-arrays.cpkt")),
)

registerKotlinVmConformance(
    taskName = "testKotlinToStringVmConformance",
    taskDescription = "Executes virtual toString, scalar boxes and lazy Any assertion messages.",
    artifactTask = ":compiler-k2:generateToStringConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/to-string.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-to-string-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TO_STRING_ARTIFACT",
    conformanceScenario = "to-string",
)

registerKotlinVmConformance(
    taskName = "testKotlinExceptionsVmConformance",
    taskDescription = "Executes explicit managed exceptions across Guest calls.",
    artifactTask = ":compiler-k2:generateExceptionsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-exceptions-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT",
    conformanceScenario = "exceptions",
    additionalArtifacts = mapOf(
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_CAUGHT" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.caught.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_FINALLY" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.finally.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_STDLIB" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.stdlib.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_TASKS" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.tasks.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_SUSPEND" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.suspend.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_ARITHMETIC" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.arithmetic.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_OPERATIONS" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.operations.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_HOST_TASKS" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.host-tasks.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_HOST_STATE" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.host-state.cpkt"),
        "COMPUKTER_KOTLIN_EXCEPTIONS_ARTIFACT_HOST_FILESYSTEM" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/exceptions.cpkt.host-filesystem.cpkt"),
    ),
)

registerKotlinVmConformance(
    taskName = "testKotlinTextStdlibVmConformance",
    taskDescription = "Executes guest text helpers under sliced VM budgets.",
    artifactTask = ":compiler-k2:generateTextStdlibConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-text-stdlib.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-text-stdlib-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TEXT_STDLIB_ARTIFACT",
    conformanceScenario = "text-stdlib",
)

registerKotlinVmConformance(
    taskName = "testCompilerArtifactVmConformance",
    taskDescription = "Verifies Kotlin executable Artifact v1 output with the pinned Compukter VM.",
    artifactTask = ":compiler-artifact:test",
    artifact = project(":compiler-artifact").layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-artifact-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT",
    conformanceScenario = "executable",
)
registerKotlinVmConformance(
    taskName = "testKotlinSubsetVmConformance",
    taskDescription = "Verifies K2-lowered Kotlin subset output with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateKotlinSubsetConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SUBSET_ARTIFACT",
    conformanceScenario = "subset",
)
registerKotlinVmConformance(
    taskName = "testKotlinNamedCallsVmConformance",
    taskDescription = "Verifies that Guest calls with intrinsic names retain their K2-resolved targets.",
    artifactTask = ":compiler-k2:generateNamedCallsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-named-calls.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-named-calls-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_NAMED_CALLS_ARTIFACT",
    conformanceScenario = "named-calls",
)
registerKotlinVmConformance(
    taskName = "testKotlinDispatchVmConformance",
    taskDescription = "Executes K2-lowered class and interface dispatch with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateDispatchConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-dispatch.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-dispatch-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_DISPATCH_ARTIFACT",
    conformanceScenario = "dispatch",
)
registerKotlinVmConformance(
    taskName = "testKotlinObjectModelVmConformance",
    taskDescription = "Executes K2-lowered sealed, data, and enum Guest objects with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateObjectModelConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-object-model.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-object-model-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_OBJECT_MODEL_ARTIFACT",
    conformanceScenario = "object-model",
)
registerKotlinVmConformance(
    taskName = "testKotlinPropertyAccessorsVmConformance",
    taskDescription = "Executes K2-lowered Guest class property accessors with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generatePropertyAccessorsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-property-accessors.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-property-accessors-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PROPERTY_ACCESSORS_ARTIFACT",
    conformanceScenario = "property-accessors",
)
registerKotlinVmConformance(
    taskName = "testKotlinAbstractPropertiesVmConformance",
    taskDescription = "Executes abstract Guest class and interface property dispatch with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateAbstractPropertiesConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-abstract-properties.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-abstract-properties-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_ABSTRACT_PROPERTIES_ARTIFACT",
    conformanceScenario = "abstract-properties",
)
registerKotlinVmConformance(
    taskName = "testKotlinInterfaceDefaultsVmConformance",
    taskDescription = "Executes Guest interface default methods and accessors with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateInterfaceDefaultsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-interface-defaults.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-interface-defaults-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INTERFACE_DEFAULTS_ARTIFACT",
    conformanceScenario = "interface-defaults",
)
registerKotlinVmConformance(
    taskName = "testKotlinInterfaceSuperVmConformance",
    taskDescription = "Executes qualified Guest interface super calls with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateInterfaceSuperConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-interface-super.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-interface-super-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INTERFACE_SUPER_ARTIFACT",
    conformanceScenario = "interface-super",
)
registerKotlinVmConformance(
    taskName = "testKotlinMutableFieldsVmConformance",
    taskDescription = "Executes K2-lowered mutable Guest class fields with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateMutableFieldsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-mutable-fields.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-mutable-fields-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MUTABLE_FIELDS_ARTIFACT",
    conformanceScenario = "mutable-fields",
)
registerKotlinVmConformance(
    taskName = "testKotlinClassInitializationVmConformance",
    taskDescription = "Executes K2-lowered Guest class initialization with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateClassInitializationConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-class-initialization.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-class-initialization-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_CLASS_INITIALIZATION_ARTIFACT",
    conformanceScenario = "class-initialization",
)
registerKotlinVmConformance(
    taskName = "testKotlinConstructorDefaultsVmConformance",
    taskDescription = "Executes Guest primary constructor defaults with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateConstructorDefaultsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-constructor-defaults.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-constructor-defaults-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_CONSTRUCTOR_DEFAULTS_ARTIFACT",
    conformanceScenario = "constructor-defaults",
)
registerKotlinVmConformance(
    taskName = "testKotlinAdaptedConstructorsVmConformance",
    taskDescription = "Executes adapted Guest constructor references with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateAdaptedConstructorsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-adapted-constructors.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-adapted-constructors-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_ADAPTED_CONSTRUCTORS_ARTIFACT",
    conformanceScenario = "adapted-constructors",
)
registerKotlinVmConformance(
    taskName = "testKotlinPrimitivesVmConformance",
    taskDescription = "Executes all Guest primitive families and operator boundary cases in the pinned VM.",
    artifactTask = ":compiler-k2-engine:generatePrimitivesConformanceArtifact",
    artifact = project(":compiler-k2-engine").layout.buildDirectory.file("generated/conformance/kotlin-primitives.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-primitives-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PRIMITIVES_ARTIFACT",
    conformanceScenario = "primitives",
)
registerKotlinVmConformance(
    taskName = "testKotlinInlineBlocksVmConformance",
    taskDescription = "Executes test-normalized Guest inline blocks and target-aware returns in the pinned VM.",
    artifactTask = ":compiler-k2-engine:generateInlineBlocksConformanceArtifact",
    artifact = project(":compiler-k2-engine").layout.buildDirectory.file("generated/conformance/kotlin-inline-blocks.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-inline-blocks-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INLINE_BLOCKS_ARTIFACT",
    conformanceScenario = "inline-blocks",
)
registerKotlinVmConformance(
    taskName = "testKotlinFunctionValuesVmConformance",
    taskDescription = "Executes K2-lowered zero-argument function values with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateFunctionValuesConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-function-values.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-function-values-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_FUNCTION_VALUES_ARTIFACT",
    conformanceScenario = "function-values",
)
registerKotlinVmConformance(
    taskName = "testKotlinTransparentCallVmConformance",
    taskDescription = "Executes an ordinary K2 project call across VM-task blocking with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateTransparentCallConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/transparent-call.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-transparent-call-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TRANSPARENT_CALL_ARTIFACT",
    conformanceScenario = "transparent-call",
)
registerKotlinVmConformance(
    taskName = "testKotlinTasksVmConformance",
    taskDescription = "Executes cooperative K2 Guest tasks with independent host requests.",
    artifactTask = ":compiler-k2:generateTasksConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/tasks.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-tasks-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TASKS_ARTIFACT",
    conformanceScenario = "tasks",
)
registerKotlinVmConformance(
    taskName = "testKotlinTimerVmConformance",
    taskDescription = "Executes a K2-produced server-tick Guest task delay request.",
    artifactTask = ":compiler-k2:generateTimerConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/timer.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-timer-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TIMER_ARTIFACT",
    conformanceScenario = "timer",
)
registerKotlinVmConformance(
    taskName = "testKotlinWhenVmConformance",
    taskDescription = "Executes bounded K2 when branches with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateWhenConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/when.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-when-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_WHEN_ARTIFACT",
    conformanceScenario = "when",
)
registerKotlinVmConformance(
    taskName = "testKotlinArgvVmConformance",
    taskDescription = "Executes K2 Array<String> entry arguments with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateArgvConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/argv.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-argv-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_ARGV_ARTIFACT",
    conformanceScenario = "argv",
)
registerKotlinVmConformance(
    taskName = "testKotlinPlatformScalarVmConformance",
    taskDescription = "Executes a bounded K2 platform-scalar precondition with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generatePlatformScalarConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/platform-scalar.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-platform-scalar-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT",
    conformanceScenario = "platform-scalar",
)
registerKotlinVmConformance(
    taskName = "testKotlinIntLoopsVmConformance",
    taskDescription = "Executes allocation-free K2 Int loops with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateIntLoopsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-int-loops.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-int-loops-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT",
    conformanceScenario = "int-loops",
)
registerKotlinVmConformance(
    taskName = "testKotlinIntArrayVmConformance",
    taskDescription = "Executes specialized K2 IntArray operations with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateIntArrayConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-int-array.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-int-array-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INT_ARRAY_ARTIFACT",
    conformanceScenario = "int-array",
)
registerKotlinVmConformance(
    taskName = "testKotlinReferenceArrayVmConformance",
    taskDescription = "Executes typed Guest class reference arrays with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateReferenceArrayConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-reference-array.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-reference-array-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_REFERENCE_ARRAY_ARTIFACT",
    conformanceScenario = "reference-array",
)
registerKotlinVmConformance(
    taskName = "testKotlinNullableReferencesVmConformance",
    taskDescription = "Executes nullable Guest Kotlin references, safe calls, and Elvis with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateNullableReferencesConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-nullable-references.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-nullable-references-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_NULLABLE_REFERENCE_ARTIFACT",
    conformanceScenario = "nullable-references",
)
registerKotlinVmConformance(
    taskName = "testKotlinLongVmConformance",
    taskDescription = "Executes Guest Kotlin Long arithmetic, conversions, comparisons, and text with the pinned VM.",
    artifactTask = ":compiler-k2:generateLongConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-long.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-long-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LONG_ARTIFACT",
    conformanceScenario = "long",
)
registerKotlinVmConformance(
    taskName = "testKotlinGenericFunctionsVmConformance",
    taskDescription = "Executes specialized Guest generic functions with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateGenericFunctionsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-generic-functions.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-generic-functions-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_GENERIC_FUNCTIONS_ARTIFACT",
    conformanceScenario = "generic-functions",
)
registerKotlinVmConformance(
    taskName = "testKotlinGenericCellVmConformance",
    taskDescription = "Executes a specialized Guest generic class with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateGenericCellConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-generic-cell.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-generic-cell-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_GENERIC_CELL_ARTIFACT",
    conformanceScenario = "generic-cell",
)
registerKotlinVmConformance(
    taskName = "testKotlinGenericInterfaceVmConformance",
    taskDescription = "Executes concrete Guest generic interface dispatch with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateGenericInterfaceConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-generic-interface.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-generic-interface-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_GENERIC_INTERFACE_ARTIFACT",
    conformanceScenario = "generic-interface",
)
registerKotlinVmConformance(
    taskName = "testKotlinProviderDefaultsVmConformance",
    taskDescription = "Executes inherited generic provider selection with typed predicates on the pinned VM.",
    artifactTask = ":compiler-k2:generateProviderDefaultsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-provider-defaults.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-provider-defaults-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PROVIDER_DEFAULTS_ARTIFACT",
    conformanceScenario = "provider-defaults",
    additionalArtifacts = mapOf("COMPUKTER_KOTLIN_STRICT_FIRST_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-strict-first.cpkt")),
)
registerKotlinVmConformance(
    taskName = "testKotlinPeripheralQueriesVmConformance",
    taskDescription = "Executes canonical peripheral selection and snapshot cleanup on the pinned VM.",
    artifactTask = ":compiler-k2:generatePeripheralQueriesConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-peripheral-queries.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-peripheral-queries-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PERIPHERAL_QUERIES_ARTIFACT",
    conformanceScenario = "peripheral-queries",
)
registerKotlinVmConformance(
    taskName = "testKotlinSingletonProvidersVmConformance",
    taskDescription = "Executes managed singleton identity and companion provider dispatch on the pinned VM.",
    artifactTask = ":compiler-k2:generateSingletonProvidersConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-singleton-providers.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-singleton-providers-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SINGLETON_PROVIDERS_ARTIFACT",
    conformanceScenario = "singleton-providers",
    additionalArtifacts = mapOf(
        "COMPUKTER_KOTLIN_ADDON_PROVIDERS_ARTIFACT" to
            project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-addon-providers.cpkt"),
    ),
)
registerKotlinVmConformance(
    taskName = "testKotlinListVmConformance",
    taskDescription = "Executes typed read-only Guest lists with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateListConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-list.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-list-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LIST_ARTIFACT",
    conformanceScenario = "list",
)
registerKotlinVmConformance(
    taskName = "testKotlinListAnyVmConformance",
    taskDescription = "Verifies covariant Int list reads through Any with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateListAnyConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-list-any.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-list-any-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LIST_ANY_ARTIFACT",
    conformanceScenario = "list-any",
)
registerKotlinVmConformance(
    taskName = "testKotlinMfvcVmConformance",
    taskDescription = "Executes multi-field value classes through direct and managed use sites with the pinned VM.",
    artifactTask = ":compiler-k2:generateMfvcConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-mfvc.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-mfvc-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MFVC_ARTIFACT",
    conformanceScenario = "mfvc",
    additionalArtifacts = mapOf("COMPUKTER_KOTLIN_MFVC_ADDON_ARTIFACT" to
        project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-mfvc.cpkt.addon.cpkt")),
)
registerKotlinVmConformance(
    taskName = "testKotlinValueClassBoxesVmConformance",
    taskDescription = "Executes boxed value classes and nominal collection elements with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateValueClassBoxesConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-value-class-boxes.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-value-class-boxes-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_VALUE_CLASS_BOXES_ARTIFACT",
    conformanceScenario = "value-class-boxes",
    additionalArtifacts =
        mapOf(
            "COMPUKTER_KOTLIN_VALUE_CLASS_BOXES_ADDON_ARTIFACT" to
                project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-value-class-boxes.cpkt.addon.cpkt"),
        ),
)
registerKotlinVmConformance(
    taskName = "testKotlinNullableCollectionsVmConformance",
    taskDescription = "Executes nullable Int and collection elements with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateNullableCollectionsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-nullable-collections.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-nullable-collections-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_NULLABLE_COLLECTIONS_ARTIFACT",
    conformanceScenario = "nullable-collections",
)
registerKotlinVmConformance(
    taskName = "testKotlinCollectionSelectionVmConformance",
    taskDescription = "Executes nullable collection selection with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateCollectionSelectionConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-collection-selection.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-collection-selection-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_COLLECTION_SELECTION_ARTIFACT",
    conformanceScenario = "collection-selection",
)
registerKotlinVmConformance(
    taskName = "testKotlinFoldVmConformance",
    taskDescription = "Executes Iterable fold with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateFoldConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-fold.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-fold-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_FOLD_ARTIFACT",
    conformanceScenario = "fold",
)
registerKotlinVmConformance(
    taskName = "testKotlinMapVmConformance",
    taskDescription = "Executes Iterable map with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateMapConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-map.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-map-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MAP_ARTIFACT",
    conformanceScenario = "map",
)
registerKotlinVmConformance(
    taskName = "testKotlinScopeVmConformance",
    taskDescription = "Executes Guest Kotlin scope functions with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateScopeConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-scope.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-scope-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SCOPE_ARTIFACT",
    conformanceScenario = "scope",
)
registerKotlinVmConformance(
    taskName = "testKotlinMapNotNullVmConformance",
    taskDescription = "Executes Iterable mapNotNull with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateMapNotNullConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-map-not-null.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-map-not-null-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MAP_NOT_NULL_ARTIFACT",
    conformanceScenario = "map-not-null",
)
registerKotlinVmConformance(
    taskName = "testKotlinDestinationVmConformance",
    taskDescription = "Executes Iterable destination operations with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateDestinationConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-destination.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-destination-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_DESTINATION_ARTIFACT",
    conformanceScenario = "destination",
)
registerKotlinVmConformance(
    taskName = "testKotlinFilterVmConformance",
    taskDescription = "Executes Iterable filter with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateFilterConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-filter.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-filter-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_FILTER_ARTIFACT",
    conformanceScenario = "filter",
)
registerKotlinVmConformance(
    taskName = "testKotlinFilterNotNullVmConformance",
    taskDescription = "Executes Iterable filterNotNull with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateFilterNotNullConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-filter-not-null.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-filter-not-null-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_FILTER_NOT_NULL_ARTIFACT",
    conformanceScenario = "filter-not-null",
)
registerKotlinVmConformance(
    taskName = "testKotlinHashCollectionsVmConformance",
    taskDescription = "Executes bounded Guest hash maps and sets with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateHashCollectionsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-hash-collections-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_HASH_COLLECTIONS_ARTIFACT",
    conformanceScenario = "hash-collections",
    additionalArtifacts = mapOf(
        "COMPUKTER_KOTLIN_HASH_INVENTORY_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.inventory.cpkt"),
        "COMPUKTER_KOTLIN_HASH_PAIR_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.pair.cpkt"),
        "COMPUKTER_KOTLIN_HASH_FACTORY_VALUES_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.factory-values.cpkt"),
        "COMPUKTER_KOTLIN_HASH_FACTORIES_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.factories.cpkt"),
        "COMPUKTER_KOTLIN_HASH_VALUES_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.values.cpkt"),
        "COMPUKTER_KOTLIN_HASH_PRIMITIVES_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.primitives.cpkt"),
        "COMPUKTER_KOTLIN_HASH_FAILURE_ARTIFACT" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-hash-collections.cpkt.failure.cpkt"),
    ) + (0..5).associate { batch ->
        "COMPUKTER_KOTLIN_HASH_SCALARS_$batch" to project(":compiler-k2").layout.buildDirectory.file("generated/conformance/hash-scalars-$batch.cpkt")
    },
)
registerKotlinVmConformance(
    taskName = "testKotlinMutableListVmConformance",
    taskDescription = "Executes mutable ArrayList with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateMutableListConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-mutable-list.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-mutable-list-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_MUTABLE_LIST_ARTIFACT",
    conformanceScenario = "mutable-list",
)
registerKotlinVmConformance(
    taskName = "testKotlinListAnyQuotaVmConformance",
    taskDescription = "Verifies boxed covariant list reads across VM quota slices and collection.",
    artifactTask = ":compiler-k2:generateListAnyQuotaConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-list-any-quota.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-list-any-quota-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LIST_ANY_QUOTA_ARTIFACT",
    conformanceScenario = "list-any-quota",
)
registerKotlinVmConformance(
    taskName = "testKotlinListBoundsVmConformance",
    taskDescription = "Verifies Guest list indexing traps on an out-of-range index.",
    artifactTask = ":compiler-k2:generateListBoundsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-list-bounds.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-list-bounds-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LIST_BOUNDS_ARTIFACT",
    conformanceScenario = "list-bounds",
)
registerKotlinVmConformance(
    taskName = "testKotlinListQuotaVmConformance",
    taskDescription = "Verifies Guest list iteration resumes across VM quota slices.",
    artifactTask = ":compiler-k2:generateListQuotaConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-list-quota.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-list-quota-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_LIST_QUOTA_ARTIFACT",
    conformanceScenario = "list-quota",
)
registerKotlinVmConformance(
    taskName = "testKotlinGenericLibraryVmConformance",
    taskDescription = "Executes a source-distributed generic Guest library consumer with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateGenericLibraryConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-generic-library.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-generic-library-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_GENERIC_LIBRARY_ARTIFACT",
    conformanceScenario = "generic-library",
)
registerKotlinVmConformance(
    taskName = "testKotlinFloatVmConformance",
    taskDescription = "Executes Guest Kotlin Float arithmetic, conversions, comparisons, and text with the pinned VM.",
    artifactTask = ":compiler-k2:generateFloatConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-float.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-float-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_FLOAT_ARTIFACT",
    conformanceScenario = "float",
)
registerKotlinVmConformance(
    taskName = "testKotlinDoubleVmConformance",
    taskDescription = "Executes Guest Kotlin Double arithmetic, conversions, comparisons, and text with the pinned VM.",
    artifactTask = ":compiler-k2:generateDoubleConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-double.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-double-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_DOUBLE_ARTIFACT",
    conformanceScenario = "double",
)
registerKotlinVmConformance(
    taskName = "testKotlinDoubleArrayVmConformance",
    taskDescription = "Executes Guest Kotlin DoubleArray storage, copying, iteration, and failures with the pinned VM.",
    artifactTask = ":compiler-k2:generateDoubleArrayConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-double-array.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-double-array-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_DOUBLE_ARRAY_ARTIFACT",
    conformanceScenario = "double-array",
)
registerKotlinVmConformance(
    taskName = "testKotlinStringCompareVmConformance",
    taskDescription = "Executes Guest Kotlin string ordering on the pinned VM.",
    artifactTask = ":compiler-k2:generateStringCompareConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-string-compare.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-string-compare-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_STRING_COMPARE_ARTIFACT",
    conformanceScenario = "string-compare",
)
registerKotlinVmConformance(
    taskName = "testKotlinScalarCompareVmConformance",
    taskDescription = "Executes Guest Kotlin Char and Boolean ordering on the pinned VM.",
    artifactTask = ":compiler-k2:generateScalarCompareConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-scalar-compare.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-scalar-compare-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SCALAR_COMPARE_ARTIFACT",
    conformanceScenario = "scalar-compare",
)

val buildScriptsTest = gradle.includedBuild("build-scripts").task(":test")

val verifyActiveMinecraftBaseline =
    tasks.register("verifyActiveMinecraftBaseline") {
        group = "verification"
        description = "Verifies supported Minecraft baselines and physical module ownership."
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
                "Java " + "17",
                "JDK " + "17",
                "JVM " + "17",
                "architectury-" + "neoforge",
            )

        inputs.files(activeFiles)
        inputs.files(
            fileTree(rootProject.file("modules/common")) {
                include("**/*.java", "**/*.kt", "**/*.kts")
                exclude("**/build/**")
            },
        )
        doLast {
            val expectedModuleGroups = setOf("common", "minecraft")
            val actualModuleGroups =
                rootProject.file("modules").listFiles().orEmpty()
                    .filter(File::isDirectory)
                    .map(File::getName)
                    .toSet()
            check(actualModuleGroups == expectedModuleGroups) {
                "modules must be grouped as $expectedModuleGroups, found ${actualModuleGroups.sorted()}"
            }
            listOf("v1_21_1", "v26_1").forEach { versionDirectory ->
                listOf("common", "neoforge").forEach { layer ->
                    val moduleName = "$versionDirectory-$layer"
                    check(rootProject.file("modules/minecraft/$versionDirectory/$moduleName").isDirectory) {
                        "supported Minecraft module $moduleName is missing"
                    }
                }
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

            val minecraftImportsInCommon =
                fileTree(rootProject.file("modules/common")) {
                    include("**/*.java", "**/*.kt", "**/*.kts")
                    exclude("**/build/**")
                }.files
                    .filter { file ->
                        file.useLines { lines ->
                            lines.any { line ->
                                line.trimStart().startsWith("import net.minecraft.") ||
                                    line.trimStart().startsWith("package net.minecraft.")
                            }
                        }
                    }.map { it.relativeTo(rootDir).path }
                    .sorted()
            check(minecraftImportsInCommon.isEmpty()) {
                "Minecraft declarations and imports must stay under modules/minecraft: " +
                    minecraftImportsInCommon.joinToString()
            }
        }
    }

val portableJava21Projects =
    listOf(
        ":native-runtime-api",
        ":platform-bundle",
        ":platform-k2",
        ":compiler-artifact",
        ":worker-client",
        ":tooling-runtime",
        ":compiler-client",
        ":compiler-runtime",
        ":compiler-k2-engine",
        ":compiler-k2",
        ":guest-platform",
        ":ide-core",
        ":ide-kotlin-formatter",
        ":ide-analysis-client",
        ":ide-analysis-k2",
        ":ide-client",
        ":ide-git",
        ":core",
    )

val verifyPortableJava21Bytecode =
    tasks.register("verifyPortableJava21Bytecode") {
        group = "verification"
        description = "Checks that portable production classes remain loadable on Java 21."
        val archives =
            portableJava21Projects.map { path ->
                project(path).tasks.named<Jar>("jar").flatMap(Jar::getArchiveFile)
            }
        dependsOn(portableJava21Projects.map { "$it:jar" })
        inputs.files(archives)
        doLast {
            val maximumMajorVersion = 65
            archives.forEach { archiveProvider ->
                val archive = archiveProvider.get().asFile
                java.util.zip.ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { entry ->
                            java.io.DataInputStream(zip.getInputStream(entry)).use { input ->
                                check(input.readInt() == 0xCAFEBABE.toInt()) {
                                    "invalid class file ${entry.name} in ${archive.name}"
                                }
                                input.readUnsignedShort()
                                val majorVersion = input.readUnsignedShort()
                                check(majorVersion <= maximumMajorVersion) {
                                    "portable class ${entry.name} in ${archive.name} targets class version " +
                                        "$majorVersion, expected <= $maximumMajorVersion"
                                }
                            }
                        }
                }
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
        val ffiManifest = compukterFfiRoot.resolve("Cargo.toml")
        val ffiLock = compukterVmLock
        val vmManifest = compukterVmManifest
        val componentInventory = rootProject.file("licenses/distribution-components.tsv")
        val packagedRustDependencyTree =
            providers.exec {
                workingDir(compukterVmRoot)
                commandLine(
                    "cargo",
                    "tree",
                    "--locked",
                    "--offline",
                    "-p",
                    "compukter-ffi",
                    "--edges",
                    "normal,build",
                    "--prefix",
                    "none",
                    "--format",
                    "{p}",
                )
            }.standardOutput.asText

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
                packagedRustDependencyTree
                    .get()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { line -> line.substringBefore(" (*)").substringBefore(" (") }
                    .map { identity -> identity.substringBeforeLast(' ') to identity.substringAfterLast(' ').removePrefix("v") }
                    .filterNot { (component, _) -> component == "compukter-ffi" || component == "compukter-vm" }
                    .distinct()
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                    .toList()
            check(actualRust == expectedRust) {
                "native Rust dependency inventory mismatch: expected $expectedRust, found $actualRust"
            }
        }
    }

tasks.register("verifyLocalFast") {
    description = "Runs the curated fast local JVM and build-script verification slice."
    group = "verification"
    dependsOn(buildScriptsTest)
    dependsOn(verifyActiveMinecraftBaseline)
    dependsOn(verifyPortableJava21Bytecode)
    dependsOn(verifyLicensePolicy)
    dependsOn(":core:test")
    dependsOn(":ide-client:check")
    dependsOn(":ide-analysis-client:check")
    dependsOn(":ide-analysis-k2:check")
    dependsOn(":native-runtime-api:test")
    dependsOn(":native-runtime-ffm:test")
    dependsOn(":native-runtime-jni:test")
    dependsOn(":playground:test")
    dependsOn(":v26_1-common:test")
    dependsOn(":v26_1-neoforge:test")
}

tasks.named("check") {
    dependsOn(verifyLicensePolicy)
}

val verifyAllModuleChecks =
    tasks.register("verifyAllModuleChecks") {
        description = "Runs the check lifecycle of every Gradle subproject."
        group = "verification"
        dependsOn(subprojects.map { "${it.path}:check" })
    }

val verifyLocalFunctional = tasks.register("verifyLocalFunctional") {
    description = "Runs full functional, conformance, native and packaged checks before latency measurements."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn(verifyAllModuleChecks)
    dependsOn(verifyKotlinVmConformance)
    dependsOn(testCompukterVmRust)
    dependsOn(testCompukterFfiRust)
    dependsOn(testCompukterFfiRustRelease)
    dependsOn(fmtCompukterFfiRust)
    dependsOn(clippyCompukterFfiRust)
    dependsOn(cargoBuildCompukterFfi)
    dependsOn(testCompukterJniRust)
    dependsOn(fmtCompukterJniRust)
    dependsOn(clippyCompukterJniRust)
    dependsOn(cargoBuildCompukterJni)
    dependsOn("checkCompukterVmRelease")
    dependsOn(":v1_21_1-neoforge:runGameTestServer")
    dependsOn(":v26_1-neoforge:runGameTestServer")
}

tasks.register("verifyLocalFull") {
    description = "Fully verifies the current checkout, production artifacts and isolated IDE latency targets."
    group = "verification"
    dependsOn(verifyLocalFunctional, ":v1_21_1-neoforge:visibleIdeLatencyPerformanceTest")
}

val collectionBenchmarkArtifacts = project(":compiler-k2").layout.buildDirectory.dir("generated/benchmarks/collections")
val collectionBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/collections")

tasks.register<Exec>("benchmarkCollectionRepresentations") {
    description = "Measures scalar lists, boxed storage and universal read bridges in the release VM."
    group = "benchmark"
    dependsOn(":compiler-k2:generateCollectionBenchmarkArtifacts")
    inputs.dir(collectionBenchmarkArtifacts)
    inputs.file(compilerArtifactVmConformanceHarness)
    inputs.file(compilerArtifactVmConformanceLock)
    inputs.file(compilerArtifactVmConformanceHarness.resolveSibling("collections_bench.rs"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(collectionBenchmarkReports)
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "test", "--release", "--locked", "--offline", "--manifest-path",
        compilerArtifactVmConformanceHarness.absolutePath, "--test", "collections_bench", "--",
        collectionBenchmarkArtifacts.get().asFile.absolutePath,
        collectionBenchmarkReports.get().asFile.absolutePath, "7",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/collection-benchmark").absolutePath)
}

val objectArrayBenchmarkArtifacts = project(":compiler-k2").layout.buildDirectory.dir("generated/benchmarks/object-arrays")

val transientBenchmarkArtifacts = project(":compiler-k2").layout.buildDirectory.dir("generated/benchmarks/transient-allocations")
val transientBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/transient-allocations")

tasks.register<Exec>("benchmarkTransientAllocations") {
    description = "Measures loop, fold, and captured-variable costs at a 16 KiB Guest heap."
    group = "benchmark"
    dependsOn(":compiler-k2:generateTransientAllocationBenchmarkArtifacts")
    inputs.dir(transientBenchmarkArtifacts)
    inputs.file(compilerArtifactVmConformanceHarness)
    inputs.file(compilerArtifactVmConformanceLock)
    inputs.file(compilerArtifactVmConformanceHarness.resolveSibling("object_arrays_bench.rs"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(transientBenchmarkReports)
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "test", "--release", "--locked", "--offline", "--manifest-path",
        compilerArtifactVmConformanceHarness.absolutePath, "--test", "object_arrays_bench", "--",
        transientBenchmarkArtifacts.get().asFile.absolutePath,
        transientBenchmarkReports.get().asFile.absolutePath, "3", "8", "fixed-heap=16384",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/collection-benchmark").absolutePath)
}
// Compile the same VM sources without cfg(test) register instrumentation.
val productionGuestBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/guest-production")

tasks.register<Exec>("benchmarkGuestProductionLoops") {
    description = "Measures compiled Guest loops through the untraced production interpreter."
    group = "benchmark"
    dependsOn(":compiler-k2:generateTransientAllocationBenchmarkArtifacts")
    inputs.dir(transientBenchmarkArtifacts)
    inputs.file(compukterVmRoot.resolve("Cargo.toml"))
    inputs.file(compukterVmRoot.resolve("Cargo.lock"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(productionGuestBenchmarkReports.map { it.dir("loops") })
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "run", "--release", "--locked", "--offline", "--manifest-path",
        compukterVmRoot.resolve("Cargo.toml").absolutePath,
        "--features", "guest-benchmark", "--bin", "guest-benchmark", "--",
        transientBenchmarkArtifacts.get().asFile.absolutePath,
        productionGuestBenchmarkReports.get().dir("loops").asFile.absolutePath,
        "7", "16384", "untraced",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/guest-benchmark").absolutePath)
}

val objectArrayBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/object-arrays")

tasks.register<Exec>("benchmarkObjectArrayHeap") {
    description = "Measures construction and complete-operation Guest heap budgets for object arrays."
    group = "benchmark"
    dependsOn(":compiler-k2:generateObjectArrayBenchmarkArtifacts")
    inputs.dir(objectArrayBenchmarkArtifacts)
    inputs.file(compilerArtifactVmConformanceHarness)
    inputs.file(compilerArtifactVmConformanceLock)
    inputs.file(compilerArtifactVmConformanceHarness.resolveSibling("object_arrays_bench.rs"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(objectArrayBenchmarkReports)
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "test", "--release", "--locked", "--offline", "--manifest-path",
        compilerArtifactVmConformanceHarness.absolutePath, "--test", "object_arrays_bench", "--",
        objectArrayBenchmarkArtifacts.get().asFile.absolutePath,
        objectArrayBenchmarkReports.get().asFile.absolutePath, "3", "62",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/collection-benchmark").absolutePath)
}

val objectCollectionBenchmarkArtifacts = project(":compiler-k2").layout.buildDirectory.dir("generated/benchmarks/object-collections")
val objectCollectionBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/object-collections")

tasks.register<Exec>("benchmarkObjectCollectionHeap") {
    description = "Measures construction and complete-operation Guest heap budgets for object collections."
    group = "benchmark"
    dependsOn(":compiler-k2:generateObjectCollectionBenchmarkArtifacts")
    inputs.dir(objectCollectionBenchmarkArtifacts)
    inputs.file(compilerArtifactVmConformanceHarness)
    inputs.file(compilerArtifactVmConformanceLock)
    inputs.file(compilerArtifactVmConformanceHarness.resolveSibling("object_arrays_bench.rs"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(objectCollectionBenchmarkReports)
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "test", "--release", "--locked", "--offline", "--manifest-path",
        compilerArtifactVmConformanceHarness.absolutePath, "--test", "object_arrays_bench", "--",
        objectCollectionBenchmarkArtifacts.get().asFile.absolutePath,
        objectCollectionBenchmarkReports.get().asFile.absolutePath, "3", "14",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/collection-benchmark").absolutePath)
}

val collectionReuseCount = providers.gradleProperty("compukterCollectionReuseCount").orElse("1024").get().toInt().also {
    require(it > 0 && it % 2 == 0) { "compukterCollectionReuseCount must be a positive even number" }
}
val collectionReuseDirectory = if (collectionReuseCount == 1024) "collection-reuse" else "collection-reuse-$collectionReuseCount"
val collectionReuseBenchmarkArtifacts = project(":compiler-k2").layout.buildDirectory.dir("generated/benchmarks/$collectionReuseDirectory")
val collectionReuseBenchmarkReports = layout.buildDirectory.dir("reports/benchmarks/$collectionReuseDirectory")

tasks.register<Exec>("benchmarkCollectionReuse") {
    description = "Measures repeated Guest collection reuse and GC work at a fixed heap budget."
    group = "benchmark"
    dependsOn(":compiler-k2:generateCollectionReuseBenchmarkArtifacts")
    inputs.dir(collectionReuseBenchmarkArtifacts)
    inputs.file(compilerArtifactVmConformanceHarness)
    inputs.file(compilerArtifactVmConformanceLock)
    inputs.file(compilerArtifactVmConformanceHarness.resolveSibling("object_arrays_bench.rs"))
    inputs.dir(compukterVmRoot.resolve("src"))
    outputs.dir(collectionReuseBenchmarkReports)
    outputs.upToDateWhen { false }
    commandLine(
        "cargo", "test", "--release", "--locked", "--offline", "--manifest-path",
        compilerArtifactVmConformanceHarness.absolutePath, "--test", "object_arrays_bench", "--",
        collectionReuseBenchmarkArtifacts.get().asFile.absolutePath,
        collectionReuseBenchmarkReports.get().asFile.absolutePath, "3", "3", "fixed-heap",
    )
    environment("CARGO_TARGET_DIR", rootProject.file(".toolchain/build/cargo/collection-benchmark").absolutePath)
}
