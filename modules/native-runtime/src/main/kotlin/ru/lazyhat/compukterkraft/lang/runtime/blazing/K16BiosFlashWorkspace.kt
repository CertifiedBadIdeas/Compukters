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

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object K16BiosFlashWorkspace {
    const val DEFAULT_BIOS_FLASH_RESOURCE: String = "firmware/k16-bios.kflash"
    const val BIOS_FLASH_FILENAME: String = "bios.kflash"

    fun prepareBiosFlash(
        workspace: Path,
        resourcePath: String = DEFAULT_BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = K16BiosFlashWorkspace::class.java.classLoader,
    ): Path {
        val biosFlashPath = workspace.resolve(BIOS_FLASH_FILENAME)
        if (biosFlashPath.exists()) {
            validateBiosFlashBytes(biosFlashPath.readBytes(), source = biosFlashPath.toString())
            return biosFlashPath
        }
        val bytes = loadBiosFlashResource(resourcePath, classLoader)
        validateBiosFlashBytes(bytes, source = resourcePath)
        workspace.createDirectories()
        biosFlashPath.writeBytes(bytes)
        return biosFlashPath
    }

    fun flashBiosFlash(
        workspace: Path,
        source: Path,
    ): Path {
        check(source.exists()) { "K16 BIOS flash source not found: $source" }
        val bytes = source.readBytes()
        validateBiosFlashBytes(bytes, source = source.toString())
        return writeBiosFlash(workspace, bytes)
    }

    fun restoreBundledBiosFlash(
        workspace: Path,
        resourcePath: String = DEFAULT_BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = K16BiosFlashWorkspace::class.java.classLoader,
    ): Path {
        val bytes = loadBiosFlashResource(resourcePath, classLoader)
        validateBiosFlashBytes(bytes, source = resourcePath)
        return writeBiosFlash(workspace, bytes)
    }

    fun loadBiosFlashResource(
        resourcePath: String = DEFAULT_BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = K16BiosFlashWorkspace::class.java.classLoader,
    ): ByteArray =
        classLoader
            .getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("K16 BIOS flash resource not found: $resourcePath")

    private fun writeBiosFlash(
        workspace: Path,
        bytes: ByteArray,
    ): Path {
        workspace.createDirectories()
        val biosFlashPath = workspace.resolve(BIOS_FLASH_FILENAME)
        biosFlashPath.writeBytes(bytes)
        return biosFlashPath
    }

    private fun validateBiosFlashBytes(
        bytes: ByteArray,
        source: String,
    ) {
        check(bytes.isNotEmpty()) { "K16 BIOS flash is empty: $source" }
        check(bytes.size % 2 == 0) {
            "K16 BIOS flash must contain 16-bit instruction bytes: $source has ${bytes.size} bytes"
        }
    }
}
