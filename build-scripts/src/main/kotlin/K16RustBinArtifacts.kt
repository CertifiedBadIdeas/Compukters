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

import java.io.File

object K16RustBinArtifacts {
    fun deleteOutputs(
        targetDir: File,
        binName: String,
        profile: String,
    ) {
        val profileDir = profileDir(targetDir, profile)
        profileDir.resolve(binName).delete()
        profileDir.resolve("$binName.d").delete()
        val cargoBinPrefix = artifactPrefix(binName)
        deleteMatchingEntries(profileDir.resolve("deps")) {
            it.startsWith("$binName-") ||
                it.startsWith("$cargoBinPrefix-")
        }
        deleteMatchingEntries(profileDir.resolve(".fingerprint")) {
            it.startsWith("$binName-") ||
                it.startsWith("$cargoBinPrefix-")
        }
        deleteMatchingEntries(profileDir.resolve("incremental")) {
            it.startsWith("$binName-") ||
                it.startsWith("$cargoBinPrefix-")
        }
    }

    fun copy(
        targetDir: File,
        binName: String,
        output: File,
        profile: String,
    ) {
        val artifact = find(targetDir, binName, profile)
        output.parentFile.mkdirs()
        artifact.copyTo(output, overwrite = true)
    }

    fun find(
        targetDir: File,
        binName: String,
        profile: String,
    ): File {
        val cargoBinPrefix = artifactPrefix(binName)
        val depsDir = profileDir(targetDir, profile).resolve("deps")
        val artifacts =
            depsDir
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.startsWith("$cargoBinPrefix-") &&
                        !it.name.endsWith(".d")
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        check(artifacts.size == 1) {
            "Expected exactly one linked K16 Rust $profile bin artifact for $binName in $depsDir, found ${artifacts.size}"
        }
        val artifact = artifacts.single()
        check(artifact.isFile) {
            "Expected linked K16 Rust $profile bin artifact for $binName at $artifact"
        }
        return artifact
    }

    fun profileDir(
        targetDir: File,
        profile: String,
    ): File = targetDir.resolve("k16-unknown-kraftos/$profile")

    private fun artifactPrefix(binName: String): String {
        return binName.replace('-', '_')
    }

    private fun deleteMatchingEntries(
        directory: File,
        matches: (String) -> Boolean,
    ) {
        directory
            .listFiles()
            ?.filter { matches(it.name) }
            ?.forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
    }
}
