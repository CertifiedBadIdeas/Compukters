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
    base
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
}

val k16VmNativePlatform = currentK16VmNativePlatform()
val k16ToolchainPin = readK16ToolchainPin()
val downloadedK16ToolchainArchives = layout.buildDirectory.dir("k16-toolchain-archives")
val packagedK16ToolchainArchives = layout.buildDirectory.dir("k16-toolchain-packages")
val k16ToolchainInstallRoot = defaultK16ToolchainRoot(k16ToolchainPin)
val k16SourceBuiltDevToolchainInstallRoot = sourceBuiltDevK16ToolchainRoot(k16ToolchainPin)
val k16ToolchainArchive = downloadedK16ToolchainArchives.map { it.file(k16ToolchainPin.archive) }
val k16ToolchainArchiveUrl =
    providers
        .gradleProperty("k16ToolchainArchiveUrl")
        .orElse("${k16ToolchainPin.artifactBaseUrl.trimEnd('/')}/${k16ToolchainPin.archive}")
val cleanWorkspaceSkipDirs =
    setOf(
        ".git",
        ".gradle",
        ".gradle-sandbox",
        ".idea",
        ".toolchain",
    )
val k16LlvmSourceRoot = rootProject.file(providers.gradleProperty("k16LlvmSourceDir").orElse("toolchains/Compukter-Kraft-llvm").get())
val k16RustSourceRoot = rootProject.file(providers.gradleProperty("k16RustSourceDir").orElse("toolchains/Compukter-Kraft-rust").get())
val k16RustBuildRoot = rootProject.file(providers.gradleProperty("k16RustBuildDir").orElse(".toolchain/build/rust/k16").get())
val k16HostToolsTargetRoot =
    rootProject.file(providers.gradleProperty("k16HostToolsTargetDir").orElse(".toolchain/build/cargo/k16-tools").get())
val k16HostVmTargetRoot =
    rootProject.file(providers.gradleProperty("k16HostVmTargetDir").orElse(".toolchain/build/cargo/k16-vm").get())
val k16RustBootstrapConfig = k16RustBuildRoot.resolve("bootstrap.toml")
val k16RustBootstrapProbeMarker = k16RustBuildRoot.resolve("bootstrap-probe.ok")
val k16PrepareToolchainMarker = rootProject.file(".toolchain/build/k16-prepare/${k16ToolchainModeName()}.ok")
val k16BootstrapHost = k16RustHostTargetTriple()
val k16LlvmHostTarget =
    when (k16BootstrapHost.substringBefore("-")) {
        "x86_64" -> "X86"
        "aarch64" -> "AArch64"
        else -> error("Unsupported K16 LLVM host target for Rust bootstrap host: $k16BootstrapHost")
    }
val k16BuildJobs =
    providers.gradleProperty("k16BuildJobs")
        .orElse(Runtime.getRuntime().availableProcessors().toString())
        .get()
val k16LlvmBuildJobs =
    providers.gradleProperty("k16LlvmBuildJobs")
        .orElse(k16BuildJobs)
        .get()
val k16RustBuildJobs =
    providers.gradleProperty("k16RustBuildJobs")
        .orElse(k16BuildJobs)
        .get()
val k16HostToolsBuildJobs =
    providers.gradleProperty("k16HostToolsBuildJobs")
        .orElse(k16BuildJobs)
        .get()
val k16LlvmBuildRoot = rootProject.file(providers.gradleProperty("k16LlvmBuildDir").orElse(".toolchain/build/llvm/k16-min").get())
val k16LlvmConfig = k16LlvmBuildRoot.resolve("bin/llvm-config")
val k16BuiltCargo = k16RustBuildRoot.resolve("$k16BootstrapHost/stage0/bin/cargo")
val k16BuiltRustc = k16RustBuildRoot.resolve("$k16BootstrapHost/stage1/bin/rustc")
val k16BuiltLd = k16HostToolsTargetRoot.resolve("release/k16-ld")
val k16BuiltCli = k16HostToolsTargetRoot.resolve("release/k16")
val k16RustcDriverLibRoot = k16BuiltRustc.parentFile.parentFile.resolve("lib")
val k16RustcHostLibRoot = k16RustcDriverLibRoot.resolve("rustlib/$k16BootstrapHost/lib")
val k16LlvmHostLibraryTargets =
    when (k16LlvmHostTarget) {
        "X86" -> listOf("LLVMMCA", "LLVMX86TargetMCA")
        "AArch64" -> emptyList()
        else -> error("Unsupported K16 LLVM host library target set: $k16LlvmHostTarget")
    }
val k16LlvmBuildTargets =
    listOf(
        "FileCheck",
        "LLVMLTO",
        "clang",
        "llvm-config",
        "llvm-ar",
        "llvm-as",
        "llvm-cov",
        "llvm-dis",
        "llvm-link",
        "llvm-nm",
        "llvm-objcopy",
        "llvm-objdump",
        "llvm-profdata",
        "llvm-readobj",
        "llvm-size",
        "llvm-strip",
        "llc",
        "opt",
    ) + k16LlvmHostLibraryTargets

fun k16ToolchainUsesPrebuiltBase(): Boolean =
    when (k16ToolchainModeName()) {
        "prebuilt" -> true
        "source-built-dev", "local" -> false
        else -> error("unreachable")
    }

fun requireDirectory(
    directory: File,
    label: String,
): File {
    check(directory.isDirectory) {
        "$label is missing: $directory"
    }
    check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) {
        "$label must not be a symlink: $directory"
    }
    return directory
}

fun requireBuiltFile(
    file: File,
    label: String,
    producer: String,
    executable: Boolean = false,
): File {
    check(file.isFile) {
        "$producer did not produce $label at $file"
    }
    check(!java.nio.file.Files.isSymbolicLink(file.toPath())) {
        "$producer produced $label as a symlink, which is not allowed: $file"
    }
    check(!executable || file.canExecute()) {
        "$producer produced $label without executable permissions: $file"
    }
    return file
}

fun requireBuiltFileMatching(
    directory: File,
    glob: String,
    label: String,
    producer: String,
) {
    requireDirectory(directory, "$producer $label directory")
    val matcher = directory.toPath().fileSystem.getPathMatcher("glob:$glob")
    val match =
        directory
            .listFiles()
            ?.firstOrNull { file -> file.isFile && matcher.matches(file.toPath().fileName) }
    check(match != null) {
        "$producer did not produce $label matching $glob in $directory"
    }
}

fun cleanWorkspaceTargets(): List<File> {
    val repositoryRoot = rootProject.projectDir
    return repositoryRoot
        .walkTopDown()
        .onEnter { file ->
            file == repositoryRoot ||
                (file.name !in cleanWorkspaceSkipDirs && !file.resolve(".git").exists())
        }
        .filter { file ->
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

val testK16HostVmRust =
    tasks.register<Exec>("testK16HostVmRust") {
        description = "Runs host/k16-vm Rust tests."
        group = "verification"
        workingDir(rootProject.file("host/k16-vm"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.toml"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.lock"))
        inputs.dir(rootProject.file("host/k16-vm/src"))
        inputs.property("k16BuildJobs", k16BuildJobs)
        commandLine("cargo", "test", "-j", k16BuildJobs)
        environment("CARGO_TARGET_DIR", k16HostVmTargetRoot.absolutePath)
    }

val testK16HostToolsRust =
    tasks.register<Exec>("testK16HostToolsRust") {
        description = "Runs host/k16-tools Rust tests."
        group = "verification"
        dependsOn("prepareK16Toolchain")
        workingDir(rootProject.file("host/k16-tools"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.toml"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.lock"))
        inputs.dir(rootProject.file("host/k16-tools/src"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.toml"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.lock"))
        inputs.dir(rootProject.file("host/k16-vm/src"))
        inputs.file(rootProject.file("guest/kraftos/abi/Cargo.toml"))
        inputs.dir(rootProject.file("guest/kraftos/abi/src"))
        inputs.property("k16BuildJobs", k16BuildJobs)
        commandLine("cargo", "test", "-j", k16BuildJobs)
        environment("CARGO_TARGET_DIR", k16HostToolsTargetRoot.absolutePath)

        doFirst {
            val toolchain = resolveK16Toolchain()
            environment("K16_CARGO", toolchain.cargo.absolutePath)
            environment("K16_RUSTC", toolchain.rustc.absolutePath)
            environment("K16_LD", toolchain.linker.absolutePath)
            environment("K16_TOOL", toolchain.cli.absolutePath)
        }
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

tasks.register("verifyK16Firmware") {
    description = "Runs focused K16 firmware build-surface and image architecture verification."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:verifyK16FirmwareArchitecture")
}

tasks.register("verifyK16Runtime") {
    description = "Runs the K16 native runtime shell smoke verification slice."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:verifyK16Runtime")
}

tasks.register("verifyK16Profiling") {
    description = "Runs all dedicated K16 profiling verification workloads."
    group = "verification"
    dependsOn("profileK16RuntimeWait")
    dependsOn("profileK16RuntimeTextIo")
    dependsOn("profileK16ManyVmServerBudget")
}

tasks.register("verifyLocalFull") {
    description = "Runs local JVM tests, K16 focused verification, and host Rust tests."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn("verifyK16Firmware")
    dependsOn("verifyK16Runtime")
    dependsOn(testK16HostVmRust)
    dependsOn(testK16HostToolsRust)
}

tasks.register("profileK16RuntimeWait") {
    description = "Runs the bundled K16 runtime wait profiling workload."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:profileK16RuntimeWait")
}

tasks.register("profileK16RuntimeTextIo") {
    description = "Runs the bundled K16 runtime terminal text IO profiling workload."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:profileK16RuntimeTextIo")
}

tasks.register("profileK16ManyVmServerBudget") {
    description = "Runs the bundled K16 many-VM server budget profiling workload."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:profileK16ManyVmServerBudget")
}

val configureK16Llvm =
    tasks.register<Exec>("configureK16Llvm") {
        description = "Configures the source-built K16 LLVM tree in .toolchain/build."
        group = "k16"
        inputs.dir(k16LlvmSourceRoot.resolve("llvm"))
        inputs.dir(k16LlvmSourceRoot.resolve("clang"))
        inputs.property("k16LlvmHostTarget", k16LlvmHostTarget)
        outputs.file(k16LlvmBuildRoot.resolve("CMakeCache.txt"))
        commandLine(
            "cmake",
            "-S",
            k16LlvmSourceRoot.resolve("llvm").absolutePath,
            "-B",
            k16LlvmBuildRoot.absolutePath,
            "-DLLVM_ENABLE_PROJECTS=clang",
            "-DLLVM_TARGETS_TO_BUILD=$k16LlvmHostTarget",
            "-DLLVM_EXPERIMENTAL_TARGETS_TO_BUILD=K16",
        )

        doFirst {
            requireDirectory(k16LlvmSourceRoot.resolve("llvm"), "K16 LLVM source directory")
        }
    }

val buildK16Llvm =
    tasks.register<Exec>("buildK16Llvm") {
        description = "Builds the patched K16 LLVM tools needed by Rust bootstrap."
        group = "k16"
        dependsOn(configureK16Llvm)
        inputs.dir(k16LlvmSourceRoot.resolve("llvm"))
        inputs.dir(k16LlvmSourceRoot.resolve("clang"))
        inputs.property("k16LlvmHostTarget", k16LlvmHostTarget)
        inputs.property("k16LlvmBuildJobs", k16LlvmBuildJobs)
        inputs.property("k16LlvmBuildTargets", k16LlvmBuildTargets)
        outputs.file(k16LlvmConfig)
        outputs.file(k16LlvmBuildRoot.resolve("bin/clang"))
        outputs.file(k16LlvmBuildRoot.resolve("bin/FileCheck"))
        outputs.file(k16LlvmBuildRoot.resolve("lib/libLLVMLTO.a"))
        commandLine(
            listOf(
                "cmake",
                "--build",
                k16LlvmBuildRoot.absolutePath,
                "--parallel",
                k16LlvmBuildJobs,
                "--target",
            ) + k16LlvmBuildTargets,
        )
    }

val writeK16RustBootstrapConfig =
    tasks.register("writeK16RustBootstrapConfig") {
        description = "Writes the Rust bootstrap config that points at the source-built K16 LLVM."
        group = "k16"
        dependsOn(buildK16Llvm)
        inputs.property("k16RustBuildRoot", k16RustBuildRoot.absolutePath)
        inputs.property("k16LlvmConfig", k16LlvmConfig.absolutePath)
        outputs.file(k16RustBootstrapConfig)

        doLast {
            requireBuiltFile(k16LlvmConfig, "llvm-config", "buildK16Llvm")
            k16RustBootstrapConfig.parentFile.mkdirs()
            k16RustBootstrapConfig.writeText(
                """
                [build]
                build-dir = "${k16RustBuildRoot.absolutePath}"

                [llvm]
                download-ci-llvm = false

                [target.$k16BootstrapHost]
                llvm-config = "${k16LlvmConfig.absolutePath}"
                """.trimIndent() + "\n",
            )
        }
    }

val probeK16RustBootstrap =
    tasks.register<Exec>("probeK16RustBootstrap") {
        description = "Checks that the Rust and LLVM source checkouts are compatible for K16 bootstrap."
        group = "k16"
        dependsOn(buildK16Llvm)
        inputs.dir(k16RustSourceRoot)
        inputs.file(k16LlvmConfig)
        inputs.property("k16LlvmHostTarget", k16LlvmHostTarget)
        inputs.property("k16RustBuildRoot", k16RustBuildRoot.absolutePath)
        inputs.property("k16BootstrapHost", k16BootstrapHost)
        outputs.file(k16RustBootstrapProbeMarker)
        commandLine(rootProject.file("tools/k16-rustc-bootstrap-probe.sh").absolutePath)
        environment("K16_RUST_SRC", k16RustSourceRoot.absolutePath)
        environment("K16_LLVM_CONFIG", k16LlvmConfig.absolutePath)
        environment("K16_LLVM_HOST_TARGET", k16LlvmHostTarget)
        environment("K16_RUST_BUILD_DIR", k16RustBuildRoot.absolutePath)
        environment("K16_RUST_HOST", k16BootstrapHost)

        doFirst {
            requireDirectory(k16RustSourceRoot, "K16 Rust source directory")
            requireBuiltFile(k16RustSourceRoot.resolve("x.py"), "x.py", "K16 Rust source checkout")
            requireBuiltFile(k16LlvmConfig, "llvm-config", "buildK16Llvm")
        }
        doLast {
            k16RustBootstrapProbeMarker.parentFile.mkdirs()
            k16RustBootstrapProbeMarker.writeText(
                """
                rustSource=${k16RustSourceRoot.absolutePath}
                llvmConfig=${k16LlvmConfig.absolutePath}
                llvmHostTarget=$k16LlvmHostTarget
                rustBuildRoot=${k16RustBuildRoot.absolutePath}
                rustHost=$k16BootstrapHost
                """.trimIndent() + "\n",
            )
        }
    }

val buildK16Rustc =
    tasks.register<Exec>("buildK16Rustc") {
        description = "Builds the patched stage1 K16 rustc with the source-built K16 LLVM."
        group = "k16"
        dependsOn(probeK16RustBootstrap)
        dependsOn(writeK16RustBootstrapConfig)
        workingDir(k16RustSourceRoot)
        inputs.dir(k16RustSourceRoot)
        inputs.file(k16RustBootstrapConfig)
        inputs.property("k16RustBuildTargets", listOf("compiler/rustc", "library/std"))
        inputs.property("k16RustBuildJobs", k16RustBuildJobs)
        outputs.file(k16BuiltRustc)
        outputs.dir(k16RustcHostLibRoot)
        outputs.upToDateWhen {
            k16RustcHostLibRoot
                .listFiles()
                ?.any { file -> file.isFile && file.name.startsWith("libstd-") && file.name.endsWith(".rlib") } == true
        }
        commandLine(
            k16RustSourceRoot.resolve("x.py").absolutePath,
            "build",
            "-j",
            k16RustBuildJobs,
            "--warnings",
            "warn",
            "--config",
            k16RustBootstrapConfig.absolutePath,
            "compiler/rustc",
            "library/std",
        )

        doFirst {
            requireBuiltFile(k16RustSourceRoot.resolve("x.py"), "x.py", "K16 Rust source checkout")
            requireBuiltFile(k16RustBootstrapConfig, "bootstrap.toml", "writeK16RustBootstrapConfig")
        }
        doLast {
            requireBuiltFileMatching(k16RustcHostLibRoot, "libstd-*.rlib", "host libstd", "buildK16Rustc")
        }
    }

val buildK16HostTools =
    tasks.register<Exec>("buildK16HostTools") {
        description = "Builds K16 host tools such as k16-ld into .toolchain/build."
        group = "k16"
        workingDir(rootProject.file("host/k16-tools"))
        inputs.dir(rootProject.file("host/k16-tools/src"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.toml"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.lock"))
        inputs.dir(rootProject.file("host/k16-vm/src"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.toml"))
        inputs.dir(rootProject.file("guest/kraftos/abi/src"))
        inputs.file(rootProject.file("guest/kraftos/abi/Cargo.toml"))
        inputs.property("k16HostToolsBuildJobs", k16HostToolsBuildJobs)
        outputs.file(k16BuiltLd)
        outputs.file(k16BuiltCli)
        commandLine("cargo", "build", "--release", "--bins", "-j", k16HostToolsBuildJobs)
        environment("CARGO_TARGET_DIR", k16HostToolsTargetRoot.absolutePath)
    }

val stageBuiltK16Toolchain =
    tasks.register<Sync>("stageBuiltK16Toolchain") {
        description = "Stages the source-built K16 toolchain into the pinned .toolchain install layout."
        group = "k16"
        dependsOn(buildK16Rustc)
        dependsOn(buildK16HostTools)
        into(k16ToolchainInstallRoot)
        from(k16BuiltCargo) {
            into("bin")
            rename { "cargo" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltRustc) {
            into("bin")
            rename { "rustc" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltLd) {
            into("bin")
            rename { "k16-ld" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltCli) {
            into("bin")
            rename { "k16" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16RustcDriverLibRoot) {
            into("lib")
            include("librustc_driver*.so")
            include("rustlib/src/rust/library/**")
        }
        from(k16RustcHostLibRoot) {
            into("lib/rustlib/$k16BootstrapHost/lib")
        }

        doFirst {
            requireBuiltFile(k16BuiltCargo, "cargo", "buildK16Rustc", executable = true)
            requireBuiltFile(k16BuiltRustc, "rustc", "buildK16Rustc", executable = true)
            requireBuiltFile(k16BuiltLd, "k16-ld", "buildK16HostTools", executable = true)
            requireBuiltFile(k16BuiltCli, "k16", "buildK16HostTools", executable = true)
            requireDirectory(k16RustcDriverLibRoot, "source-built rustc runtime library directory")
            requireDirectory(k16RustcHostLibRoot, "source-built rustc host runtime library directory")
            requireBuiltFileMatching(k16RustcHostLibRoot, "libstd-*.rlib", "host libstd", "buildK16Rustc")
        }

        doLast {
            k16ToolchainInstallRoot.resolve("manifest.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "pin": "${k16ToolchainPin.pin}",
                  "host": "${k16VmNativePlatform.id}",
                  "archive": "${k16ToolchainPin.archive}",
                  "source": "source-built-gradle-stage"
                }
                """.trimIndent() + "\n",
            )
            validateK16ToolchainPath(
                root = k16ToolchainInstallRoot,
                origin = "stageBuiltK16Toolchain",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

tasks.register("buildK16ToolchainFromSource") {
    description = "Builds LLVM, Rust, and K16 host tools from source, then stages the K16 toolchain."
    group = "k16"
    dependsOn(stageBuiltK16Toolchain)
}

tasks.register("prepareBuiltK16Toolchain") {
    description = "Builds and prepares the source-built K16 toolchain, then prints its environment."
    group = "k16"
    dependsOn(stageBuiltK16Toolchain)

    doLast {
        val toolchain =
            validateK16ToolchainPath(
                root = k16ToolchainInstallRoot,
                origin = "stageBuiltK16Toolchain",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        println("export K16_CARGO=${toolchain.cargo.absolutePath}")
        println("export K16_RUSTC=${toolchain.rustc.absolutePath}")
        println("export K16_LD=${toolchain.linker.absolutePath}")
        println("export K16_TOOL=${toolchain.cli.absolutePath}")
    }
}

tasks.register<Zip>("packageBuiltK16Toolchain") {
    description = "Packages the source-built staged K16 toolchain into the pinned host archive shape."
    group = "k16"
    dependsOn(stageBuiltK16Toolchain)
    inputs.file(k16ToolchainConfigFile())
    archiveFileName.set(k16ToolchainPin.archive)
    destinationDirectory.set(packagedK16ToolchainArchives)
    from(k16ToolchainInstallRoot)
    eachFile {
        if (k16ToolchainPin.requiredExecutables.contains(relativePath.pathString)) {
            permissions { unix("rwxr-xr-x") }
        }
    }

    doFirst {
        validateK16ToolchainPath(
            root = k16ToolchainInstallRoot,
            origin = "stageBuiltK16Toolchain",
            requiredExecutables = k16ToolchainPin.requiredExecutables,
        )
    }

    doLast {
        val archive = archiveFile.get().asFile
        println("archive=${archive.absolutePath}")
        println("sha256=${sha256Hex(archive)}")
    }
}

tasks.register<GenerateK16FontTablesTask>("generateK16FontTables") {
    description = "Generates Rust terminal font tables from the K16 bitmap font source."
    group = "k16"
    fontFile.set(layout.projectDirectory.file("assets/k16/fonts/k16-terminal.font"))
    guestRustOutput.set(layout.projectDirectory.file("guest/kraftos/kernel/src/generated/terminal_font.rs"))
}

tasks.register<GenerateK16FontSpecimenTask>("generateK16FontSpecimen") {
    description = "Generates a Markdown specimen report for the K16 bitmap font source."
    group = "k16"
    fontFile.set(layout.projectDirectory.file("assets/k16/fonts/k16-terminal.font"))
    output.set(layout.buildDirectory.file("reports/k16-font/k16-terminal-specimen.md"))
}

val downloadK16ToolchainArchive =
    tasks.register("downloadK16ToolchainArchive") {
        description = "Downloads the pinned prebuilt K16 toolchain archive for the current host."
        group = "k16"
        inputs.file(k16ToolchainConfigFile())
        inputs.property("archiveUrl", k16ToolchainArchiveUrl)
        inputs.property("archiveSha256", k16ToolchainPin.sha256)
        outputs.file(k16ToolchainArchive)
        onlyIf {
            k16ToolchainUsesPrebuiltBase() &&
                explicitK16ToolchainRoot() == null &&
                !isK16ToolchainInstalled(k16ToolchainInstallRoot, k16ToolchainPin.requiredExecutables)
        }

        doLast {
            val archiveFile = k16ToolchainArchive.get().asFile
            archiveFile.parentFile.mkdirs()
            val downloadUrl = k16ToolchainArchiveUrl.get()
            val tempFile = File("${archiveFile.absolutePath}.tmp")
            tempFile.delete()
            try {
                java.net.URI(downloadUrl).toURL().openStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.copyTo(archiveFile, overwrite = true)
            } catch (error: Exception) {
                throw GradleException(
                    "Failed to download pinned K16 toolchain archive from $downloadUrl. " +
                        "Publish the prebuilt archive or pass -Pk16ToolchainDir=/absolute/path/to/k16-toolchain.",
                    error,
                )
            } finally {
                tempFile.delete()
            }
        }
    }

val installK16Toolchain =
    tasks.register<Copy>("installK16Toolchain") {
        description = "Installs the pinned prebuilt K16 toolchain archive into .toolchain."
        group = "k16"
        dependsOn(downloadK16ToolchainArchive)
        inputs.file(k16ToolchainConfigFile())
        inputs.property("archiveSha256", k16ToolchainPin.sha256)
        from({ zipTree(k16ToolchainArchive.get().asFile) })
        into(k16ToolchainInstallRoot)
        onlyIf {
            k16ToolchainUsesPrebuiltBase() &&
                explicitK16ToolchainRoot() == null &&
                !isK16ToolchainInstalled(k16ToolchainInstallRoot, k16ToolchainPin.requiredExecutables)
        }

        doFirst {
            verifyK16ToolchainArchiveChecksum(k16ToolchainArchive.get().asFile, k16ToolchainPin)
        }

        doLast {
            validateK16ToolchainPath(
                root = k16ToolchainInstallRoot,
                origin = "installed pinned prebuilt archive '${k16ToolchainPin.archive}'",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

val stageK16Toolchain =
    tasks.register<Sync>("stageK16Toolchain") {
        description = "Stages explicitly provided K16 toolchain binaries into .toolchain."
        group = "k16"
        into(k16ToolchainInstallRoot)
        onlyIf {
            k16ToolchainModeName() == "local"
        }
        from({
            if (k16ToolchainModeName() == "local") {
                requireK16ToolchainInputFile("k16CargoPath", "cargo")
            } else {
                emptyList<File>()
            }
        }) {
            into("bin")
            rename { "cargo" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from({
            if (k16ToolchainModeName() == "local") {
                requireK16ToolchainInputFile("k16RustcPath", "rustc")
            } else {
                emptyList<File>()
            }
        }) {
            into("bin")
            rename { "rustc" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from({
            if (k16ToolchainModeName() == "local") {
                requireK16ToolchainInputFile("k16LdPath", "k16-ld")
            } else {
                emptyList<File>()
            }
        }) {
            into("bin")
            rename { "k16-ld" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from({
            if (k16ToolchainModeName() == "local") {
                requireK16ToolchainInputFile("k16ToolPath", "k16")
            } else {
                emptyList<File>()
            }
        }) {
            into("bin")
            rename { "k16" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from({
            if (k16ToolchainModeName() == "local") {
                k16RustcRuntimeLibDir()
            } else {
                emptyList<File>()
            }
        }) {
            into("lib")
            include("librustc_driver*.so")
            include("rustlib/src/rust/library/**")
        }
        from({
            if (k16ToolchainModeName() == "local") {
                k16RustcHostRuntimeLibDir()
            } else {
                emptyList<File>()
            }
        }) {
            val hostTriple = k16RustHostTargetTriple()
            into("lib/rustlib/$hostTriple/lib")
        }

        doLast {
            k16ToolchainInstallRoot.resolve("manifest.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "pin": "${k16ToolchainPin.pin}",
                  "host": "${k16VmNativePlatform.id}",
                  "archive": "${k16ToolchainPin.archive}",
                  "source": "explicit-gradle-stage"
                }
                """.trimIndent() + "\n",
            )
            validateK16ToolchainPath(
                root = k16ToolchainInstallRoot,
                origin = "stageK16Toolchain",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

val stageK16SourceBuiltDevToolchain =
    tasks.register<Sync>("stageK16SourceBuiltDevToolchain") {
        description = "Stages a source-built K16 development toolchain without updating the pinned prebuilt workspace."
        group = "k16"
        dependsOn(buildK16Rustc)
        dependsOn(buildK16HostTools)
        into(k16SourceBuiltDevToolchainInstallRoot)
        onlyIf {
            k16ToolchainModeName() == "source-built-dev"
        }
        from(k16BuiltCargo) {
            into("bin")
            rename { "cargo" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltRustc) {
            into("bin")
            rename { "rustc" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltLd) {
            into("bin")
            rename { "k16-ld" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16BuiltCli) {
            into("bin")
            rename { "k16" }
            filePermissions { unix("rwxr-xr-x") }
        }
        from(k16RustcDriverLibRoot) {
            into("lib")
            include("librustc_driver*.so")
            include("rustlib/src/rust/library/**")
        }
        from(k16RustcHostLibRoot) {
            into("lib/rustlib/$k16BootstrapHost/lib")
        }

        doFirst {
            check(explicitK16ToolchainRoot() == null) {
                "k16ToolchainMode=source-built-dev stages into a dedicated .toolchain workspace and does not accept k16ToolchainDir"
            }
            requireBuiltFile(k16BuiltCargo, "cargo", "buildK16Rustc", executable = true)
            requireBuiltFile(k16BuiltRustc, "rustc", "buildK16Rustc", executable = true)
            requireBuiltFile(k16BuiltLd, "k16-ld", "buildK16HostTools", executable = true)
            requireBuiltFile(k16BuiltCli, "k16", "buildK16HostTools", executable = true)
            requireDirectory(k16RustcDriverLibRoot, "source-built rustc runtime library directory")
            requireDirectory(k16RustcHostLibRoot, "source-built rustc host runtime library directory")
            requireBuiltFileMatching(k16RustcHostLibRoot, "libstd-*.rlib", "host libstd", "buildK16Rustc")
        }

        doLast {
            k16SourceBuiltDevToolchainInstallRoot.resolve("manifest.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "pin": "${k16ToolchainPin.pin}",
                  "host": "${k16VmNativePlatform.id}",
                  "archive": "${k16ToolchainPin.archive}",
                  "source": "source-built-dev-gradle-stage"
                }
                """.trimIndent() + "\n",
            )
            validateK16ToolchainPath(
                root = k16SourceBuiltDevToolchainInstallRoot,
                origin = "stageK16SourceBuiltDevToolchain",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

val testK16SourceBuiltDevToolchain =
    tasks.register<Exec>("testK16SourceBuiltDevToolchain") {
        description = "Runs K16 host tool smoke tests against the staged source-built development toolchain."
        group = "verification"
        dependsOn(stageK16SourceBuiltDevToolchain)
        workingDir(rootProject.file("host/k16-tools"))
        inputs.dir(rootProject.file("host/k16-tools/src"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.toml"))
        inputs.file(rootProject.file("host/k16-tools/Cargo.lock"))
        inputs.dir(rootProject.file("host/k16-vm/src"))
        inputs.file(rootProject.file("host/k16-vm/Cargo.toml"))
        inputs.dir(rootProject.file("guest/kraftos/abi/src"))
        inputs.file(rootProject.file("guest/kraftos/abi/Cargo.toml"))
        inputs.dir(k16SourceBuiltDevToolchainInstallRoot)
        inputs.property("k16HostToolsBuildJobs", k16HostToolsBuildJobs)
        commandLine(
            k16SourceBuiltDevToolchainInstallRoot.resolve("bin/cargo").absolutePath,
            "test",
            "-j",
            k16HostToolsBuildJobs,
        )
        environment("K16_CARGO", k16SourceBuiltDevToolchainInstallRoot.resolve("bin/cargo").absolutePath)
        environment("K16_RUSTC", k16SourceBuiltDevToolchainInstallRoot.resolve("bin/rustc").absolutePath)
        environment(
            "CARGO_TARGET_DIR",
            k16HostToolsTargetRoot.resolve("source-built-dev-tests").absolutePath,
        )

        doFirst {
            check(k16ToolchainModeName() == "source-built-dev") {
                "testK16SourceBuiltDevToolchain requires -Pk16ToolchainMode=source-built-dev (use ./gradlew-sandbox-dev)"
            }
            requireBuiltFile(
                k16SourceBuiltDevToolchainInstallRoot.resolve("bin/cargo"),
                "cargo",
                "stageK16SourceBuiltDevToolchain",
                executable = true,
            )
            requireBuiltFile(
                k16SourceBuiltDevToolchainInstallRoot.resolve("bin/rustc"),
                "rustc",
                "stageK16SourceBuiltDevToolchain",
                executable = true,
            )
        }
    }

val prepareK16Toolchain =
    tasks.register("prepareK16Toolchain") {
        description = "Prepares the selected K16 toolchain mode and validates the resolved install layout."
        group = "k16"
        dependsOn(installK16Toolchain)
        dependsOn(stageK16Toolchain)
        if (k16ToolchainModeName() == "source-built-dev") {
            dependsOn(stageK16SourceBuiltDevToolchain)
        }
        inputs.property("k16ToolchainMode", providers.gradleProperty("k16ToolchainMode").orElse("prebuilt"))
        inputs.file(k16ToolchainConfigFile())
        inputs.property("k16ToolchainDir", providers.gradleProperty("k16ToolchainDir").orElse(""))
        inputs.property("k16ToolPath", providers.gradleProperty("k16ToolPath").orElse(""))
        outputs.file(k16PrepareToolchainMarker)

        doLast {
            val toolchain = resolveK16Toolchain()
            k16PrepareToolchainMarker.parentFile.mkdirs()
            k16PrepareToolchainMarker.writeText(
                """
                mode=${k16ToolchainModeName()}
                cargo=${toolchain.cargo.absolutePath}
                rustc=${toolchain.rustc.absolutePath}
                linker=${toolchain.linker.absolutePath}
                cli=${toolchain.cli.absolutePath}
                """.trimIndent() + "\n",
            )
        }
    }

tasks.register("printK16ToolchainEnv") {
    description = "Prints shell exports for the selected K16 toolchain."
    group = "k16"
    dependsOn(prepareK16Toolchain)

    doLast {
        val toolchain = resolveK16Toolchain()
        println("export K16_CARGO=${toolchain.cargo.absolutePath}")
        println("export K16_RUSTC=${toolchain.rustc.absolutePath}")
        println("export K16_LD=${toolchain.linker.absolutePath}")
        println("export K16_TOOL=${toolchain.cli.absolutePath}")
    }
}
