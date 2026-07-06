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

package ru.lazyhat.compukterkraft.lang.runtime.kraftos

import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.file.Path

data class KraftOsArtifactPaths(
    val biosFlashPath: Path,
    val storage0VolumePath: Path,
)

object KraftOsArtifacts {
    const val BIOS_FLASH_RESOURCE: String = K16BiosFlashWorkspace.DEFAULT_BIOS_FLASH_RESOURCE
    const val STORAGE0_VOLUME_RESOURCE: String = K16SystemVolumeWorkspace.DEFAULT_STORAGE0_VOLUME_RESOURCE
    const val BIOS_FLASH_FILENAME: String = K16BiosFlashWorkspace.BIOS_FLASH_FILENAME
    const val STORAGE0_VOLUME_FILENAME: String = K16SystemVolumeWorkspace.STORAGE0_VOLUME_FILENAME

    fun prepareWorkspace(
        workspace: Path,
        classLoader: ClassLoader = KraftOsArtifacts::class.java.classLoader,
    ): KraftOsArtifactPaths {
        val manifest = loadBundledManifest(classLoader)
        return prepareWorkspace(
            workspace = workspace,
            biosFlashResource = manifest.biosFlash.resource,
            storage0VolumeResource = manifest.systemStorage0.resource,
            classLoader = classLoader,
        )
    }

    fun prepareWorkspace(
        workspace: Path,
        biosFlashResource: String,
        storage0VolumeResource: String,
        classLoader: ClassLoader = KraftOsArtifacts::class.java.classLoader,
    ): KraftOsArtifactPaths =
        KraftOsArtifactPaths(
            biosFlashPath =
                prepareBiosFlash(
                    workspace = workspace,
                    resourcePath = biosFlashResource,
                    classLoader = classLoader,
                ),
            storage0VolumePath =
                prepareStorage0Volume(
                    workspace = workspace,
                    resourcePath = storage0VolumeResource,
                    classLoader = classLoader,
                ),
        )

    fun loadBundledManifest(
        classLoader: ClassLoader = KraftOsArtifacts::class.java.classLoader,
    ): KraftOsArtifactManifest =
        KraftOsArtifactManifest.load(classLoader = classLoader)

    fun prepareBiosFlash(
        workspace: Path,
        resourcePath: String = BIOS_FLASH_RESOURCE,
        classLoader: ClassLoader = KraftOsArtifacts::class.java.classLoader,
    ): Path =
        K16BiosFlashWorkspace.prepareBiosFlash(
            workspace = workspace,
            resourcePath = resourcePath,
            classLoader = classLoader,
        )

    fun prepareStorage0Volume(
        workspace: Path,
        resourcePath: String = STORAGE0_VOLUME_RESOURCE,
        classLoader: ClassLoader = KraftOsArtifacts::class.java.classLoader,
    ): Path =
        K16SystemVolumeWorkspace.prepareStorage0Volume(
            workspace = workspace,
            resourcePath = resourcePath,
            classLoader = classLoader,
        )
}
