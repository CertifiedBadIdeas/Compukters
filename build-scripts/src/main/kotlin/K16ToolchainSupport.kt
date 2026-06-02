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

import groovy.json.JsonSlurper
import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

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
    val sha256: String,
    val requiredExecutables: List<String>,
)

fun Project.k16ToolchainConfigFile(): File = rootProject.file("config/k16-toolchain.json")

fun Project.readK16ToolchainPin(): K16ToolchainPin {
    val configFile = k16ToolchainConfigFile()
    val config = JsonSlurper().parse(configFile) as Map<*, *>
    val schemaVersion = config["schemaVersion"]
    check(schemaVersion.toString() == "1") {
        "Unsupported K16 toolchain config schemaVersion=$schemaVersion in $configFile"
    }
    val pin =
        config["pin"] as? String
            ?: error("K16 toolchain config is missing string field 'pin': $configFile")
    val artifactBaseUrl =
        config["artifactBaseUrl"] as? String
            ?: error("K16 toolchain config is missing string field 'artifactBaseUrl': $configFile")
    val requiredExecutables =
        (config["requiredExecutables"] as? List<*>)
            ?.map {
                it as? String
                    ?: error("K16 toolchain config has a non-string required executable in $configFile")
            }
            ?: error("K16 toolchain config is missing array field 'requiredExecutables': $configFile")
    val hosts =
        config["hosts"] as? Map<*, *>
            ?: error("K16 toolchain config is missing object field 'hosts': $configFile")
    val hostId = currentK16VmNativePlatform().id
    check(hosts.containsKey(hostId)) {
        "K16 toolchain pin '$pin' does not declare host '$hostId' in $configFile"
    }
    val host =
        hosts[hostId] as? Map<*, *>
            ?: error("K16 toolchain host '$hostId' is not an object in $configFile")
    val archive =
        host["archive"] as? String
            ?: error("K16 toolchain host '$hostId' is missing string field 'archive' in $configFile")
    check(archive.endsWith(".zip")) {
        "K16 toolchain host '$hostId' must use a .zip archive supported by the Gradle installer: $archive"
    }
    val sha256 =
        host["sha256"] as? String
            ?: error("K16 toolchain host '$hostId' is missing string field 'sha256' in $configFile")
    check(sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
        "K16 toolchain host '$hostId' has invalid sha256 in $configFile: $sha256"
    }
    return K16ToolchainPin(
        pin = pin,
        artifactBaseUrl = artifactBaseUrl,
        archive = archive,
        sha256 = sha256.lowercase(),
        requiredExecutables = requiredExecutables,
    )
}

fun Project.k16ToolchainModeName(): String {
    val mode = providers.gradleProperty("k16ToolchainMode").orElse("prebuilt").get()
    check(mode == "prebuilt" || mode == "local") {
        "k16ToolchainMode must be 'prebuilt' or 'local', got: $mode"
    }
    return mode
}

fun Project.k16ToolchainWorkspaceRoot(): File = rootProject.file(".toolchain/k16")

fun Project.defaultK16ToolchainRoot(pin: K16ToolchainPin = readK16ToolchainPin()): File =
    k16ToolchainWorkspaceRoot().resolve(pin.pin).resolve(currentK16VmNativePlatform().id)

fun Project.explicitK16ToolchainRoot(): File? {
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

fun Project.resolveK16Toolchain(): K16Toolchain {
    val pin = readK16ToolchainPin()
    return when (k16ToolchainModeName()) {
        "prebuilt" -> {
            val explicitRoot = explicitK16ToolchainRoot()
            val root = explicitRoot ?: defaultK16ToolchainRoot(pin)
            val origin =
                if (explicitRoot == null) {
                    "pinned prebuilt workspace '${pin.pin}' for ${currentK16VmNativePlatform().id}"
                } else {
                    "k16ToolchainDir"
                }
            validateK16ToolchainPath(
                root = root,
                origin = origin,
                requiredExecutables = pin.requiredExecutables,
            )
        }
        "local" -> {
            check(explicitK16ToolchainRoot() == null) {
                "k16ToolchainMode=local does not accept k16ToolchainDir; pass k16CargoPath, k16RustcPath, and k16LdPath"
            }
            validateK16ToolchainPath(
                root = defaultK16ToolchainRoot(pin),
                origin = "stageK16Toolchain",
                requiredExecutables = pin.requiredExecutables,
            )
        }
        else -> error("unreachable")
    }
}

fun Project.validateK16ToolchainPath(
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

    fun requireExecutable(file: File, label: String) {
        requireRealFile(file, label)
        check(file.canExecute()) {
            "K16 toolchain from $origin is invalid: $label must be executable: $file"
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
        requireExecutable(root.resolve(relativePath), relativePath)
    }
    val cargo = root.resolve(requiredExecutables.single { it.endsWith("/cargo") })
    val rustc = root.resolve(requiredExecutables.single { it.endsWith("/rustc") })
    val linker = root.resolve(requiredExecutables.single { it.endsWith("/k16-ld") })
    requireExecutable(cargo, "cargo")
    requireExecutable(rustc, "rustc")
    requireExecutable(linker, "k16-ld")
    return K16Toolchain(root = root, cargo = cargo, rustc = rustc, linker = linker)
}

fun Project.requireK16ToolchainInputFile(
    propertyName: String,
    label: String,
): File {
    val value =
        providers.gradleProperty(propertyName).orNull
            ?: error("stageK16Toolchain requires -P$propertyName=/absolute/path/to/$label")
    val file = File(value)
    check(file.isAbsolute) {
        "$propertyName must be an absolute path, got: $value"
    }
    check(file.isFile) {
        "$propertyName must point to an existing file, got: $file"
    }
    check(file.canExecute()) {
        "$propertyName must point to an executable file, got: $file"
    }
    check(!Files.isSymbolicLink(file.toPath())) {
        "$propertyName must not point to a symlink: $file"
    }
    return file
}

fun Project.k16RustcRuntimeLibDir(): File {
    val rustc = requireK16ToolchainInputFile("k16RustcPath", "rustc")
    val libDir = rustc.parentFile.parentFile.resolve("lib")
    check(libDir.isDirectory) {
        "k16RustcPath must belong to a Rust install layout with runtime libraries at ../lib, got: $libDir"
    }
    check(!Files.isSymbolicLink(libDir.toPath())) {
        "k16RustcPath runtime library directory must not be a symlink: $libDir"
    }
    return libDir
}

fun Project.k16RustHostTargetTriple(): String {
    val arch =
        when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported K16 Rust host architecture: ${System.getProperty("os.arch")}")
        }
    return when {
        System.getProperty("os.name").startsWith("linux", ignoreCase = true) -> "$arch-unknown-linux-gnu"
        System.getProperty("os.name").startsWith("mac", ignoreCase = true) ||
            System.getProperty("os.name").startsWith("darwin", ignoreCase = true) -> "$arch-apple-darwin"
        System.getProperty("os.name").startsWith("windows", ignoreCase = true) -> "$arch-pc-windows-msvc"
        else -> error("Unsupported K16 Rust host OS: ${System.getProperty("os.name")}")
    }
}

fun Project.k16RustcHostRuntimeLibDir(): File {
    val rustc = requireK16ToolchainInputFile("k16RustcPath", "rustc")
    val hostTriple = k16RustHostTargetTriple()
    val libDir = rustc.parentFile.parentFile.resolve("lib/rustlib/$hostTriple/lib")
    check(libDir.isDirectory) {
        "k16RustcPath must belong to a Rust bootstrap layout with host runtime libraries at $libDir"
    }
    check(!Files.isSymbolicLink(libDir.toPath())) {
        "k16RustcPath host runtime library directory must not be a symlink: $libDir"
    }
    return libDir
}

fun isK16ToolchainInstalled(
    root: File,
    requiredExecutables: List<String>,
): Boolean =
    root.isDirectory &&
        root.resolve("manifest.json").isFile &&
        requiredExecutables.all { root.resolve(it).isFile && root.resolve(it).canExecute() }

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun verifyK16ToolchainArchiveChecksum(
    archive: File,
    pin: K16ToolchainPin,
) {
    val actual = sha256Hex(archive)
    check(actual == pin.sha256) {
        "K16 toolchain archive checksum mismatch for $archive. " +
            "Expected ${pin.sha256}, got $actual. " +
            "Update config/k16-toolchain.json only after publishing the intended prebuilt artifact."
    }
}
