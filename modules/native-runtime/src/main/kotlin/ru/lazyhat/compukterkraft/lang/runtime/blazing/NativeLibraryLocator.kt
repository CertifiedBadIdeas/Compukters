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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal data class NativeLibraryPlatform(
    val id: String,
    val libraryName: String,
) {
    val resourcePath: String = "natives/$id/$libraryName"
}

internal sealed class NativeLibraryResolution {
    abstract val path: String

    data class Configured(
        override val path: String,
    ) : NativeLibraryResolution()

    data class Bundled(
        override val path: String,
        val resourcePath: String,
        val platform: NativeLibraryPlatform,
        val sha256: String,
    ) : NativeLibraryResolution()
}

internal object NativeLibraryLocator {
    const val LIBRARY_PROPERTY = "k16.vm.native.library"
    const val EXTRACT_DIR_PROPERTY = "k16.vm.native.extract.dir"

    fun resolve(): NativeLibraryResolution? =
        resolve(
            configuredPath = System.getProperty(LIBRARY_PROPERTY),
            platform = platform(),
            cacheRoot = defaultCacheRoot(),
        ) { resourcePath ->
            NativeLibraryLocator::class.java.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        }

    fun requireLibraryPath(): String =
        resolve()?.path
            ?: error(
                "Rust image VM runner requires -D$LIBRARY_PROPERTY=/absolute/path/to/${platform()?.libraryName ?: "libk16_vm.so"} " +
                    "or a bundled native resource for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
            )

    fun platform(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): NativeLibraryPlatform? {
        val arch =
            when (osArch.lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> return null
            }
        return when {
            osName.startsWith("Linux", ignoreCase = true) ->
                NativeLibraryPlatform(id = "linux-$arch", libraryName = "libk16_vm.so")
            osName.startsWith("Windows", ignoreCase = true) ->
                NativeLibraryPlatform(id = "windows-$arch", libraryName = "k16_vm.dll")
            osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) ->
                NativeLibraryPlatform(id = "macos-$arch", libraryName = "libk16_vm.dylib")
            else -> null
        }
    }

    fun resolve(
        configuredPath: String?,
        platform: NativeLibraryPlatform?,
        cacheRoot: Path,
        resourceBytes: (String) -> ByteArray?,
    ): NativeLibraryResolution? {
        configuredPath?.takeIf { it.isNotBlank() }?.let { path ->
            return NativeLibraryResolution.Configured(path = path)
        }
        platform ?: return null
        val bytes = resourceBytes(platform.resourcePath) ?: return null
        val hash = bytes.sha256()
        val targetDir = cacheRoot.resolve(platform.id).resolve(hash)
        val target = targetDir.resolve(platform.libraryName)
        Files.createDirectories(targetDir)
        if (!target.exists() || !target.bytesEqual(bytes)) {
            val temp = Files.createTempFile(targetDir, "${platform.libraryName}.", ".tmp")
            temp.outputStream().use { it.write(bytes) }
            runCatching {
                Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        }
        makeExecutable(target)
        return NativeLibraryResolution.Bundled(
            path = target.toAbsolutePath().toString(),
            resourcePath = platform.resourcePath,
            platform = platform,
            sha256 = hash,
        )
    }

    private fun defaultCacheRoot(): Path =
        System
            .getProperty(EXTRACT_DIR_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"), "compukterkraft", "native")

    private fun Path.bytesEqual(bytes: ByteArray): Boolean =
        inputStream().use { input ->
            val existing = input.readBytes()
            existing.contentEquals(bytes)
        }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun makeExecutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
    }
}
