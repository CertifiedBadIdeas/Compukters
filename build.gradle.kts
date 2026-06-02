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

val k16VmNativePlatform = currentK16VmNativePlatform()
val k16ToolchainPin = readK16ToolchainPin()
val downloadedK16ToolchainArchives = layout.buildDirectory.dir("k16-toolchain-archives")
val packagedK16ToolchainArchives = layout.buildDirectory.dir("k16-toolchain-packages")
val k16ToolchainInstallRoot = defaultK16ToolchainRoot(k16ToolchainPin)
val k16ToolchainArchive = downloadedK16ToolchainArchives.map { it.file(k16ToolchainPin.archive) }
val k16ToolchainArchiveUrl =
    providers
        .gradleProperty("k16ToolchainArchiveUrl")
        .orElse("${k16ToolchainPin.artifactBaseUrl.trimEnd('/')}/${k16ToolchainPin.archive}")

tasks.register<GenerateK16FontTablesTask>("generateK16FontTables") {
    description = "Generates Rust and Kotlin terminal font tables from the K16 bitmap font source."
    group = "k16"
    fontFile.set(layout.projectDirectory.file("assets/k16/fonts/k16-mono-5x7.font"))
    rustOutput.set(layout.projectDirectory.file("rust/host/k16-vm/src/generated/font_mono5x7.rs"))
    kotlinOutput.set(
        layout.projectDirectory.file(
            "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/GeneratedTerminalFont.kt",
        ),
    )
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
            k16ToolchainModeName() == "prebuilt" &&
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
            k16ToolchainModeName() == "prebuilt" &&
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

tasks.register<Zip>("packageK16Toolchain") {
    description = "Packages a selected local K16 toolchain directory into the pinned host archive shape."
    group = "k16"
    dependsOn(stageK16Toolchain)
    inputs.file(k16ToolchainConfigFile())
    archiveFileName.set(k16ToolchainPin.archive)
    destinationDirectory.set(packagedK16ToolchainArchives)
    from({
        val root =
            when (k16ToolchainModeName()) {
                "local" -> k16ToolchainInstallRoot
                "prebuilt" ->
                    explicitK16ToolchainRoot()
                        ?: error(
                            "packageK16Toolchain requires -Pk16ToolchainMode=local with explicit binaries or " +
                                "-Pk16ToolchainMode=prebuilt -Pk16ToolchainDir=/absolute/path/to/k16-toolchain",
                        )
                else -> error("unreachable")
            }
        validateK16ToolchainPath(
            root = root,
            origin = if (k16ToolchainModeName() == "local") "stageK16Toolchain" else "k16ToolchainDir",
            requiredExecutables = k16ToolchainPin.requiredExecutables,
        )
        root
    })

    doLast {
        val archive = archiveFile.get().asFile
        println("archive=${archive.absolutePath}")
        println("sha256=${sha256Hex(archive)}")
    }
}

val prepareK16Toolchain =
    tasks.register("prepareK16Toolchain") {
        description = "Prepares the selected K16 toolchain mode and validates the resolved install layout."
        group = "k16"
        dependsOn(installK16Toolchain)
        dependsOn(stageK16Toolchain)
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
    }
}
