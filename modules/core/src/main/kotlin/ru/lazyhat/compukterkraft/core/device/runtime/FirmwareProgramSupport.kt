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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.runtime.DeviceProgramFiles
import java.nio.charset.StandardCharsets

data class LoadedFirmwareProgramSource(
    val path: String,
    val source: String,
)

interface FirmwareProgramLoader {
    fun load(path: String = DeviceProgramFiles.BIOS_SCRIPT_NAME): LoadedFirmwareProgramSource?
}

class ClasspathFirmwareProgramLoader(
    private val classLoader: ClassLoader = ClasspathFirmwareProgramLoader::class.java.classLoader,
    private val resourceRoot: String = "firmware",
) : FirmwareProgramLoader {
    override fun load(path: String): LoadedFirmwareProgramSource? {
        val normalized = path.trimStart('/')
        if (normalized.contains("..")) return null
        val source =
            classLoader
                .getResourceAsStream("$resourceRoot/$normalized")
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                ?: return null
        return LoadedFirmwareProgramSource(normalized, source)
    }
}