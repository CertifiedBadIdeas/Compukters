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

package ru.lazyhat.compukterkraft.lang.runtime.storage

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

object RuxSystemVolumeWorkspace {
    const val DEFAULT_STORAGE0_VOLUME_RESOURCE: String = "firmware/rux16-system-storage0.ruxvol"
    const val STORAGE0_VOLUME_FILENAME: String = "storage0.ruxvol"

    fun prepareStorage0Volume(
        workspace: Path,
        resourcePath: String = DEFAULT_STORAGE0_VOLUME_RESOURCE,
        classLoader: ClassLoader = RuxSystemVolumeWorkspace::class.java.classLoader,
    ): Path {
        val storage0Path = workspace.resolve("volumes").resolve(STORAGE0_VOLUME_FILENAME)
        if (storage0Path.exists()) {
            return storage0Path
        }
        val bytes = loadStorage0VolumeResource(resourcePath, classLoader)
        check(bytes.isNotEmpty()) { "Rux16 system storage0 volume resource is empty: $resourcePath" }
        storage0Path.parent.createDirectories()
        storage0Path.writeBytes(bytes)
        return storage0Path
    }

    fun loadStorage0VolumeResource(
        resourcePath: String = DEFAULT_STORAGE0_VOLUME_RESOURCE,
        classLoader: ClassLoader = RuxSystemVolumeWorkspace::class.java.classLoader,
    ): ByteArray =
        classLoader
            .getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("Rux16 system storage0 volume resource not found: $resourcePath")
}
