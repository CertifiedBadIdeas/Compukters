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
import kotlin.io.path.writeBytes

object RuxBiosFlashWorkspace {
    const val DEFAULT_BIOS_FLASH_RESOURCE: String = "firmware/rux16-bios.flash.words"
    const val BIOS_FLASH_FILENAME: String = "bios.flash"

    fun prepareBiosFlash(
        workspace: Path,
        resourcePath: String = DEFAULT_BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = RuxBiosFlashWorkspace::class.java.classLoader,
    ): Path {
        val biosFlashPath = workspace.resolve(BIOS_FLASH_FILENAME)
        if (biosFlashPath.exists()) {
            return biosFlashPath
        }
        val bytes = loadBiosFlashResource(resourcePath, classLoader)
        check(bytes.isNotEmpty()) { "Rux16 BIOS flash resource is empty: $resourcePath" }
        workspace.createDirectories()
        biosFlashPath.writeBytes(bytes)
        return biosFlashPath
    }

    fun loadBiosFlashResource(
        resourcePath: String = DEFAULT_BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = RuxBiosFlashWorkspace::class.java.classLoader,
    ): ByteArray {
        val source =
            classLoader
                .getResourceAsStream(resourcePath)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Rux16 BIOS flash resource not found: $resourcePath")
        return decodeWords(source, resourcePath)
    }

    private fun decodeWords(
        source: String,
        resourcePath: String,
    ): ByteArray {
        val words =
            source
                .lineSequence()
                .flatMap { line ->
                    line
                        .substringBefore("#")
                        .trim()
                        .splitToSequence(Regex("\\s+"))
                }.filter { token -> token.isNotBlank() }
                .map { token ->
                    val normalized = token.removePrefix("0x").removePrefix("0X")
                    require(normalized.length in 1..4 && normalized.all(::isHexDigit)) {
                        "Invalid Rux16 BIOS flash word '$token' in $resourcePath"
                    }
                    normalized.toInt(16)
                }.toList()

        val bytes = ByteArray(words.size * 2)
        for ((index, word) in words.withIndex()) {
            bytes[index * 2] = (word and 0xff).toByte()
            bytes[index * 2 + 1] = ((word ushr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun isHexDigit(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}
