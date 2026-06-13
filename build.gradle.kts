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
val k16SourceHostToolsToolchainInstallRoot = sourceK16HostToolsToolchainRoot(k16ToolchainPin)
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
val k16RustBootstrapConfig = k16RustBuildRoot.resolve("bootstrap.toml")
val k16BootstrapHost = k16RustHostTargetTriple()
val k16LlvmHostTarget =
    when (k16BootstrapHost.substringBefore("-")) {
        "x86_64" -> "X86"
        "aarch64" -> "AArch64"
        else -> error("Unsupported K16 LLVM host target for Rust bootstrap host: $k16BootstrapHost")
    }
val k16LlvmBuildJobs =
    providers.gradleProperty("k16LlvmBuildJobs")
        .orElse(Runtime.getRuntime().availableProcessors().toString())
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
        "prebuilt", "source-host-tools" -> true
        "local" -> false
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

tasks.register("profileK16RuntimeWait") {
    description = "Runs the bundled K16 runtime wait profiling workload."
    group = "verification"
    dependsOn(":v1_21_1-neoforge:profileK16RuntimeWait")
}

val configureK16Llvm =
    tasks.register<Exec>("configureK16Llvm") {
        description = "Configures the source-built K16 LLVM tree in .toolchain/build."
        group = "k16"
        inputs.dir(k16LlvmSourceRoot.resolve("llvm"))
        inputs.property("k16LlvmHostTarget", k16LlvmHostTarget)
        outputs.file(k16LlvmBuildRoot.resolve("CMakeCache.txt"))
        commandLine(
            "cmake",
            "-S",
            k16LlvmSourceRoot.resolve("llvm").absolutePath,
            "-B",
            k16LlvmBuildRoot.absolutePath,
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
        inputs.property("k16LlvmHostTarget", k16LlvmHostTarget)
        inputs.property("k16LlvmBuildJobs", k16LlvmBuildJobs)
        inputs.property("k16LlvmBuildTargets", k16LlvmBuildTargets)
        outputs.file(k16LlvmConfig)
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
        workingDir(rootProject.file("rust/host/k16-tools"))
        inputs.dir(rootProject.file("rust/host/k16-tools/src"))
        inputs.file(rootProject.file("rust/host/k16-tools/Cargo.toml"))
        inputs.file(rootProject.file("rust/host/k16-tools/Cargo.lock"))
        inputs.dir(rootProject.file("rust/host/k16-vm/src"))
        inputs.file(rootProject.file("rust/host/k16-vm/Cargo.toml"))
        inputs.dir(rootProject.file("rust/guest/k16-abi/src"))
        inputs.file(rootProject.file("rust/guest/k16-abi/Cargo.toml"))
        outputs.file(k16BuiltLd)
        outputs.file(k16BuiltCli)
        commandLine("cargo", "build", "--release", "--bins")
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
    fontFile.set(layout.projectDirectory.file("assets/k16/fonts/k16-mono-5x7.font"))
    rustOutput.set(layout.projectDirectory.file("rust/host/k16-vm/src/generated/font_mono5x7.rs"))
    guestRustOutput.set(layout.projectDirectory.file("rust/guest/k16-kernel/src/generated/font_mono5x7.rs"))
}

tasks.register<GenerateK16FontSpecimenTask>("generateK16FontSpecimen") {
    description = "Generates a Markdown specimen report for the K16 bitmap font source."
    group = "k16"
    fontFile.set(layout.projectDirectory.file("assets/k16/fonts/k16-mono-5x7.font"))
    output.set(layout.buildDirectory.file("reports/k16-font/k16-mono-5x7-specimen.md"))
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

val stageK16SourceHostTools =
    tasks.register<Sync>("stageK16SourceHostTools") {
        description = "Stages a prebuilt-backed K16 toolchain with source-built host tools."
        group = "k16"
        dependsOn(installK16Toolchain)
        dependsOn(buildK16HostTools)
        into(k16SourceHostToolsToolchainInstallRoot)
        onlyIf {
            k16ToolchainModeName() == "source-host-tools"
        }
        from(k16ToolchainInstallRoot) {
            exclude("bin/k16-ld", "bin/k16")
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

        doFirst {
            check(explicitK16ToolchainRoot() == null) {
                "k16ToolchainMode=source-host-tools stages into a dedicated .toolchain workspace and does not accept k16ToolchainDir"
            }
            validateK16ToolchainPath(
                root = k16ToolchainInstallRoot,
                origin = "source-host-tools prebuilt base",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
            requireBuiltFile(k16BuiltLd, "k16-ld", "buildK16HostTools", executable = true)
            requireBuiltFile(k16BuiltCli, "k16", "buildK16HostTools", executable = true)
        }

        doLast {
            k16SourceHostToolsToolchainInstallRoot.resolve("manifest.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "pin": "${k16ToolchainPin.pin}",
                  "host": "${k16VmNativePlatform.id}",
                  "archive": "${k16ToolchainPin.archive}",
                  "source": "source-prebuilt-host-tools"
                }
                """.trimIndent() + "\n",
            )
            validateK16ToolchainPath(
                root = k16SourceHostToolsToolchainInstallRoot,
                origin = "stageK16SourceHostTools",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

val prepareK16Toolchain =
    tasks.register("prepareK16Toolchain") {
        description = "Prepares the selected K16 toolchain mode and validates the resolved install layout."
        group = "k16"
        dependsOn(installK16Toolchain)
        dependsOn(stageK16Toolchain)
        dependsOn(stageK16SourceHostTools)
        inputs.property("k16ToolchainMode", providers.gradleProperty("k16ToolchainMode").orElse("prebuilt"))

        doLast {
            resolveK16Toolchain()
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
