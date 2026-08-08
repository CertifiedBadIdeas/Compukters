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

import ru.lazyhat.compukterkraft.lang.runtime.storage.K16ImmutableArtifactWorkspace
import java.nio.file.Path

class K16SdkArtifacts(
    private val manifest: KraftOsArtifactManifest,
    private val workspace: K16ImmutableArtifactWorkspace,
    private val classLoader: ClassLoader = K16SdkArtifacts::class.java.classLoader,
) {
    fun resolve(identity: String): Path {
        val artifact = manifest.sdkArtifact(identity)
        val bytes =
            classLoader
                .getResourceAsStream(artifact.resource)
                ?.use { it.readBytes() }
                ?: error(
                    "bundled K16 SDK artifact resource not found: " +
                        "${artifact.resource} for identity ${artifact.identity}",
                )
        return workspace.materialize(artifact.identity, bytes)
    }
}
