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
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class BuildContext(
    val versionKey: String,
    val minecraftVersion: String,
    val javaVersion: Int,
)

data class K16VmNativePlatform(
    val id: String,
    val libraryName: String,
)

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

enum class LoaderKind(
    val lowercase: String,
) {
    FABRIC("fabric"),
    NEOFORGE("neoforge"),
}

private const val BUILD_CONTEXT_KEY = "ck.buildContext"
private const val LOADER_KIND_KEY = "ck.loaderKind"
private const val EFFECTIVE_BUILD_VERSION_KEY = "ck.effectiveBuildVersion"

fun ExtensionAware.libsCatalog(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun ExtensionAware.setBuildContext(
    versionKey: String,
    minecraftVersion: String,
    javaVersion: Int,
) {
    extraProperties()[BUILD_CONTEXT_KEY] =
        BuildContext(
            versionKey = versionKey,
            minecraftVersion = minecraftVersion,
            javaVersion = javaVersion,
        )
}

fun ExtensionAware.buildContextOrNull(): BuildContext? = (extraProperties()[BUILD_CONTEXT_KEY] as? BuildContext)

fun Project.buildContext(): BuildContext =
    buildContextOrNull()
        ?: error("Build context is not configured for $path. Apply a version convention plugin first.")

fun ExtensionAware.setLoaderKind(loaderKind: LoaderKind) {
    extraProperties()[LOADER_KIND_KEY] = loaderKind
}

fun ExtensionAware.loaderKindOrNull(): LoaderKind? = extraProperties()[LOADER_KIND_KEY] as? LoaderKind

fun Project.loaderKind(): LoaderKind =
    loaderKindOrNull()
        ?: error("Loader kind is not configured for $path. Apply a loader convention plugin first.")

fun Project.versionLibrary(aliasPrefix: String): Provider<MinimalExternalModuleDependency> =
    libsCatalog().findLibrary("$aliasPrefix-${buildContext().versionKey}").get()

fun Project.readAllModProperties(): Map<String, String> =
    file("$rootDir/config/mod.properties")
        .readLines()
        .mapNotNull { line ->
            line.indexOf('=').takeIf { it != -1 }?.let { index -> line.substring(0, index) to line.substring(index + 1) }
        }.toMap()

fun Project.readVersionedModProperties(): Map<String, String> =
    readAllModProperties()
        .let { map ->
            val minecraftVersion = buildContext().minecraftVersion

            map
                .filterKeys { it.startsWith(minecraftVersion) }
                .mapKeys { (key, _) -> key.substringAfter("${minecraftVersion}_") } +
                map
                    .filterKeys { it.startsWith("common") }
                    .mapKeys { it.key.substringAfter("common_") }
        }

fun Project.computeModArchiveVersion(): String =
    "${buildContext().minecraftVersion}-${loaderKind().lowercase}-${rootProject.effectiveBuildVersion()}"

fun Project.computeModVersion(): String = "${buildContext().minecraftVersion}-${rootProject.effectiveBuildVersion()}"

fun computeEffectiveBuildVersion(
    baseVersion: String,
    headTags: Iterable<String>,
    shortHash: String,
): String {
    val releaseTags = setOf(baseVersion, "v$baseVersion")
    return if (headTags.any { it in releaseTags }) {
        baseVersion
    } else {
        "$baseVersion-S-$shortHash"
    }
}

fun Project.effectiveBuildVersion(): String {
    val extra = rootProject.extraProperties()
    extra.getProperties()[EFFECTIVE_BUILD_VERSION_KEY]?.let { return it.toString() }
    val baseVersion = rootProject.version.toString()
    val headTags =
        gitCaptureOrNull(rootProject.projectDir, "tag", "--points-at", "HEAD")
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            ?: emptyList()
    val shortHash =
        gitCaptureOrNull(rootProject.projectDir, "rev-parse", "--short", "HEAD")
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
    val effective = computeEffectiveBuildVersion(baseVersion, headTags, shortHash)
    extra[EFFECTIVE_BUILD_VERSION_KEY] = effective
    return effective
}

fun currentK16VmNativePlatform(
    osName: String = System.getProperty("os.name"),
    osArch: String = System.getProperty("os.arch"),
): K16VmNativePlatform {
    val arch =
        when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported K16 VM native architecture: $osArch")
        }
    return when {
        osName.startsWith("linux", ignoreCase = true) ->
            K16VmNativePlatform(id = "linux-$arch", libraryName = "libk16_vm.so")
        osName.startsWith("windows", ignoreCase = true) ->
            K16VmNativePlatform(id = "windows-$arch", libraryName = "k16_vm.dll")
        osName.startsWith("mac", ignoreCase = true) || osName.startsWith("darwin", ignoreCase = true) ->
            K16VmNativePlatform(id = "macos-$arch", libraryName = "libk16_vm.dylib")
        else -> error("Unsupported K16 VM native OS: $osName")
    }
}

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
        requiredExecutables.all { root.resolve(it).isFile }

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

private fun gitCaptureOrNull(
    projectDir: File,
    vararg args: String,
): String? =
    runCatching {
        val process =
            ProcessBuilder("git", *args)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

private fun ExtensionAware.extraProperties(): ExtraPropertiesExtension = extensions.getByType<ExtraPropertiesExtension>()
