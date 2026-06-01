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

import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
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
            sourceSet("main", project(":native-runtime"))
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

val k16VmNativePlatform = currentK16VmNativePlatform()
val k16VmNativeLibrary = rootProject.layout.projectDirectory.file("rust/host/k16-vm/target/debug/${k16VmNativePlatform.libraryName}")
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareArtifacts = layout.buildDirectory.dir("generated/k16-firmware-artifacts")
val generatedK16GuestTarget = layout.buildDirectory.dir("generated/k16-guest-target")
val generatedK16BiosTarget = generatedK16GuestTarget.map { it.dir("bios") }
val generatedK16BootTarget = generatedK16GuestTarget.map { it.dir("boot") }
val generatedK16KernelTarget = generatedK16GuestTarget.map { it.dir("kernel") }
val downloadedK16ToolchainArchives = layout.buildDirectory.dir("k16-toolchain-archives")
val k16ToolsManifest = rootProject.layout.projectDirectory.file("rust/host/k16-tools/Cargo.toml")
val k16ToolsSource = rootProject.layout.projectDirectory.dir("rust/host/k16-tools/src")
val k16GuestManifest = rootProject.layout.projectDirectory.file("rust/guest/Cargo.toml")
val k16BiosManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/Cargo.toml")
val k16BiosSource = rootProject.layout.projectDirectory.file("rust/guest/k16-bios/src/main.rs")
val k16BootManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/Cargo.toml")
val k16BootSource = rootProject.layout.projectDirectory.file("rust/guest/k16-boot/src/main.rs")
val k16KernelManifest = rootProject.layout.projectDirectory.file("rust/guest/k16-kernel/Cargo.toml")
val k16KernelSource = rootProject.layout.projectDirectory.file("rust/guest/k16-kernel/src/main.rs")
val k16RustTargetSpec = rootProject.layout.projectDirectory.file("tools/k16-unknown-kraftos.json")
val k16ToolchainConfig = rootProject.layout.projectDirectory.file("config/k16-toolchain.json")
val k16BiosFlashResource = generatedK16FirmwareResources.map { it.file("firmware/k16-bios.kflash") }
val k16BootArtifact = generatedK16FirmwareArtifacts.map { it.file("kernel-loader.kb") }
val k16KernelArtifact = generatedK16FirmwareArtifacts.map { it.file("display-ok.kx") }
val k16SystemStorage0Resource = generatedK16FirmwareResources.map { it.file("firmware/k16-system-storage0.kv") }

data class K16Toolchain(
    val root: File,
    val cargo: File,
    val rustc: File,
    val linker: File,
)

data class K16ToolchainPin(
    val pin: String,
    val artifactBaseUrl: String,
    val archive: String,
    val requiredExecutables: List<String>,
)

fun readK16ToolchainPin(): K16ToolchainPin {
    val config = JsonSlurper().parse(k16ToolchainConfig.asFile) as Map<*, *>
    val schemaVersion = config["schemaVersion"]
    check(schemaVersion.toString() == "1") {
        "Unsupported K16 toolchain config schemaVersion=$schemaVersion in ${k16ToolchainConfig.asFile}"
    }
    val pin =
        config["pin"] as? String
            ?: error("K16 toolchain config is missing string field 'pin': ${k16ToolchainConfig.asFile}")
    val artifactBaseUrl =
        config["artifactBaseUrl"] as? String
            ?: error("K16 toolchain config is missing string field 'artifactBaseUrl': ${k16ToolchainConfig.asFile}")
    val requiredExecutables =
        (config["requiredExecutables"] as? List<*>)
            ?.map {
                it as? String
                    ?: error("K16 toolchain config has a non-string required executable in ${k16ToolchainConfig.asFile}")
            }
            ?: error("K16 toolchain config is missing array field 'requiredExecutables': ${k16ToolchainConfig.asFile}")
    val hosts =
        config["hosts"] as? Map<*, *>
            ?: error("K16 toolchain config is missing object field 'hosts': ${k16ToolchainConfig.asFile}")
    val hostId = k16VmNativePlatform.id
    check(hosts.containsKey(hostId)) {
        "K16 toolchain pin '$pin' does not declare host '$hostId' in ${k16ToolchainConfig.asFile}"
    }
    val host =
        hosts[hostId] as? Map<*, *>
            ?: error("K16 toolchain host '$hostId' is not an object in ${k16ToolchainConfig.asFile}")
    val archive =
        host["archive"] as? String
            ?: error("K16 toolchain host '$hostId' is missing string field 'archive' in ${k16ToolchainConfig.asFile}")
    check(archive.endsWith(".zip")) {
        "K16 toolchain host '$hostId' must use a .zip archive supported by the Gradle installer: $archive"
    }
    return K16ToolchainPin(
        pin = pin,
        artifactBaseUrl = artifactBaseUrl,
        archive = archive,
        requiredExecutables = requiredExecutables,
    )
}

val k16ToolchainPin = readK16ToolchainPin()

fun validateK16ToolchainPath(
    root: File,
    origin: String,
    requiredExecutables: List<String>,
): K16Toolchain {
    fun requireRealFile(file: File, label: String) {
        check(file.isFile) {
            "K16 toolchain from $origin is invalid: missing $label at $file"
        }
        check(!Files.isSymbolicLink(file.toPath())) {
            "K16 toolchain from $origin is invalid: $label must not be a symlink: $file"
        }
    }

    check(root.isDirectory) {
        "K16 toolchain from $origin is not installed at $root. " +
            "Install the pinned prebuilt toolchain or pass -Pk16ToolchainDir=/absolute/path/to/k16-toolchain."
    }
    check(!Files.isSymbolicLink(root.toPath())) {
        "K16 toolchain from $origin must not resolve through a symlink: $root"
    }
    requireRealFile(root.resolve("manifest.json"), "manifest")
    requiredExecutables.forEach { relativePath ->
        requireRealFile(root.resolve(relativePath), relativePath)
    }
    val cargo = root.resolve(requiredExecutables.single { it.endsWith("/cargo") })
    val rustc = root.resolve(requiredExecutables.single { it.endsWith("/rustc") })
    val linker = root.resolve(requiredExecutables.single { it.endsWith("/k16-ld") })
    requireRealFile(cargo, "cargo")
    requireRealFile(rustc, "rustc")
    requireRealFile(linker, "k16-ld")
    return K16Toolchain(root = root, cargo = cargo, rustc = rustc, linker = linker)
}

fun explicitK16ToolchainRoot(): File? {
    val explicitDir = providers.gradleProperty("k16ToolchainDir").orNull
    if (explicitDir != null) {
        val root = File(explicitDir)
        check(root.isAbsolute) {
            "k16ToolchainDir must be an absolute path, got: $explicitDir"
        }
        return root
    }
    return null
}

fun k16ToolchainCacheRoot(): File =
        providers
            .gradleProperty("k16ToolchainCacheDir")
            .map { file(it) }
            .orNull
            ?: File(System.getProperty("user.home"), ".cache/compukter-kraft/k16-toolchains")

fun defaultK16ToolchainRoot(): File {
    val hostId = k16VmNativePlatform.id
    return k16ToolchainCacheRoot().resolve(k16ToolchainPin.pin).resolve(hostId)
}

fun isK16ToolchainInstalled(root: File): Boolean =
    root.isDirectory &&
        root.resolve("manifest.json").isFile &&
        k16ToolchainPin.requiredExecutables.all { root.resolve(it).isFile }

fun resolveK16Toolchain(): K16Toolchain {
    val explicitRoot = explicitK16ToolchainRoot()
    val root = explicitRoot ?: defaultK16ToolchainRoot()
    val origin =
        if (explicitRoot == null) {
            "pinned prebuilt cache '${k16ToolchainPin.pin}' for ${k16VmNativePlatform.id}"
        } else {
            "k16ToolchainDir"
        }
    return validateK16ToolchainPath(
        root = root,
        origin = origin,
        requiredExecutables = k16ToolchainPin.requiredExecutables,
    )
}

val k16ToolchainArchive = downloadedK16ToolchainArchives.map { it.file(k16ToolchainPin.archive) }
val k16ToolchainArchiveUrl =
    providers
        .gradleProperty("k16ToolchainArchiveUrl")
        .orElse("${k16ToolchainPin.artifactBaseUrl.trimEnd('/')}/${k16ToolchainPin.archive}")

val downloadK16ToolchainArchive =
    tasks.register("downloadK16ToolchainArchive") {
        description = "Downloads the pinned prebuilt K16 toolchain archive for the current host."
        group = "k16"
        inputs.file(k16ToolchainConfig)
        inputs.property("archiveUrl", k16ToolchainArchiveUrl)
        outputs.file(k16ToolchainArchive)
        onlyIf {
            explicitK16ToolchainRoot() == null && !isK16ToolchainInstalled(defaultK16ToolchainRoot())
        }

        doLast {
            val archiveFile = k16ToolchainArchive.get().asFile
            archiveFile.parentFile.mkdirs()
            val downloadUrl = k16ToolchainArchiveUrl.get()
            val tempFile = File("${archiveFile.absolutePath}.tmp")
            tempFile.delete()
            try {
                URI(downloadUrl).toURL().openStream().use { input ->
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
        description = "Installs the pinned prebuilt K16 toolchain archive into the local cache."
        group = "k16"
        dependsOn(downloadK16ToolchainArchive)
        inputs.file(k16ToolchainConfig)
        from({ zipTree(k16ToolchainArchive.get().asFile) })
        into(defaultK16ToolchainRoot())
        onlyIf {
            explicitK16ToolchainRoot() == null && !isK16ToolchainInstalled(defaultK16ToolchainRoot())
        }

        doLast {
            validateK16ToolchainPath(
                root = defaultK16ToolchainRoot(),
                origin = "installed pinned prebuilt archive '${k16ToolchainPin.archive}'",
                requiredExecutables = k16ToolchainPin.requiredExecutables,
            )
        }
    }

fun deleteK16RustBinOutputs(
    targetDir: File,
    binName: String,
) {
    val debugDir = targetDir.resolve("k16-unknown-kraftos/debug")
    debugDir.resolve(binName).delete()
    debugDir.resolve("$binName.d").delete()
    val depsDir = targetDir.resolve("k16-unknown-kraftos/debug/deps")
    depsDir
        .listFiles()
        ?.filter { it.name.startsWith("$binName-") }
        ?.forEach { file ->
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
}

fun copyK16RustBinOutput(
    targetDir: File,
    binName: String,
    output: File,
) {
    val artifact = targetDir.resolve("k16-unknown-kraftos/debug/$binName")
    check(artifact.isFile) {
        "Expected linked K16 Rust bin artifact for $binName at $artifact"
    }
    output.parentFile.mkdirs()
    artifact.copyTo(output, overwrite = true)
}

val linkK16BiosFlash =
    tasks.register<Exec>("linkK16BiosFlash") {
        description = "Compiles and links the bundled Rust K16 BIOS bin crate into a raw BIOS flash resource."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BiosManifest)
        inputs.file(k16BiosSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16BiosFlashResource)
        dependsOn(installK16Toolchain)

        doFirst {
            val toolchain = resolveK16Toolchain()
            k16BiosFlashResource.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16BiosTarget.get().asFile, "k16-bios")
            environment("RUSTC", toolchain.rustc.absolutePath)
            environment(
                "RUSTFLAGS",
                "-C linker=${toolchain.linker.absolutePath} -C link-arg=--k16-target=bios -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                toolchain.cargo.absolutePath,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16BiosManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-bios",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16BiosTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16BiosTarget.get().asFile,
                binName = "k16-bios",
                output = k16BiosFlashResource.get().asFile,
            )
        }
    }

val compileK16SystemBoot =
    tasks.register<Exec>("compileK16SystemBoot") {
        description = "Compiles and links the bundled Rust K16 bootloader bin crate into a K16E boot artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16BootManifest)
        inputs.file(k16BootSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16BootArtifact)
        dependsOn(installK16Toolchain)

        doFirst {
            val toolchain = resolveK16Toolchain()
            k16BootArtifact.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16BootTarget.get().asFile, "k16-boot")
            environment("RUSTC", toolchain.rustc.absolutePath)
            environment(
                "RUSTFLAGS",
                "-C linker=${toolchain.linker.absolutePath} -C link-arg=--k16-target=boot -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                toolchain.cargo.absolutePath,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16BootManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-boot",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16BootTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16BootTarget.get().asFile,
                binName = "k16-boot",
                output = k16BootArtifact.get().asFile,
            )
        }
    }

val compileK16SystemKernel =
    tasks.register<Exec>("compileK16SystemKernel") {
        description = "Compiles and links the bundled Rust K16 kernel bin crate into a K16E kernel artifact."
        group = "k16"
        inputs.file(k16GuestManifest)
        inputs.file(k16KernelManifest)
        inputs.file(k16KernelSource)
        inputs.file(k16RustTargetSpec)
        inputs.file(k16ToolchainConfig)
        inputs.file(k16ToolsManifest)
        inputs.dir(k16ToolsSource)
        outputs.file(k16KernelArtifact)
        dependsOn(installK16Toolchain)

        doFirst {
            val toolchain = resolveK16Toolchain()
            k16KernelArtifact.get().asFile.parentFile.mkdirs()
            deleteK16RustBinOutputs(generatedK16KernelTarget.get().asFile, "k16-kernel")
            environment("RUSTC", toolchain.rustc.absolutePath)
            environment(
                "RUSTFLAGS",
                "-C linker=${toolchain.linker.absolutePath} -C link-arg=--k16-target=kernel -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
            )
            commandLine(
                toolchain.cargo.absolutePath,
                "rustc",
                "-Zbuild-std=core",
                "-Zjson-target-spec",
                "--manifest-path",
                k16KernelManifest.asFile.absolutePath,
                "--features",
                "k16-target",
                "--bin",
                "k16-kernel",
                "--target",
                k16RustTargetSpec.asFile.absolutePath,
                "--target-dir",
                generatedK16KernelTarget.get().asFile.absolutePath,
                "--",
                "-C",
                "panic=abort",
                "-C",
                "relocation-model=static",
                "-Cjump-tables=no",
                "-Cdebuginfo=0",
                "-Cdebug-assertions=off",
                "-Coverflow-checks=off",
                "-Zub-checks=no",
            )
        }

        doLast {
            copyK16RustBinOutput(
                targetDir = generatedK16KernelTarget.get().asFile,
                binName = "k16-kernel",
                output = k16KernelArtifact.get().asFile,
            )
        }
    }

val createK16SystemStorage0 =
    tasks.register<Exec>("createK16SystemStorage0") {
        description = "Creates the bundled K16 system storage0 volume resource."
        group = "k16"
        inputs.file(k16ToolsManifest)

        doFirst {
            k16SystemStorage0Resource.get().asFile.parentFile.mkdirs()
        }

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "init",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            "--size",
            "1048576",
        )
    }

val putK16SystemStorage0Boot =
    tasks.register<Exec>("putK16SystemStorage0Boot") {
        description = "Writes the bundled K16 bootloader into the system storage0 volume resource."
        group = "k16"
        dependsOn(createK16SystemStorage0, compileK16SystemBoot)
        inputs.file(k16ToolsManifest)
        inputs.file(k16BootArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "put-boot",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            k16BootArtifact.get().asFile.absolutePath,
        )
    }

val compileK16SystemStorage0 =
    tasks.register<Exec>("compileK16SystemStorage0") {
        description = "Writes the bundled K16 kernel into the system storage0 volume resource."
        group = "k16"
        dependsOn(putK16SystemStorage0Boot, compileK16SystemKernel)
        inputs.file(k16ToolsManifest)
        inputs.file(k16BootArtifact)
        inputs.file(k16KernelArtifact)

        commandLine(
            "cargo",
            "run",
            "--manifest-path",
            k16ToolsManifest.asFile.absolutePath,
            "--bin",
            "k16",
            "--",
            "volume",
            "put-kernel",
            k16SystemStorage0Resource.get().asFile.absolutePath,
            k16KernelArtifact.get().asFile.absolutePath,
        )
    }

sourceSets.main {
    resources.srcDir(generatedK16FirmwareResources)
}

tasks.named("processResources") {
    dependsOn(linkK16BiosFlash)
    dependsOn(compileK16SystemStorage0)
}
