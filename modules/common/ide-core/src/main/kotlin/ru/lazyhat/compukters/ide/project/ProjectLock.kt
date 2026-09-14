/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.util.Collections

data class ToolchainLockIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val codegenAbi: UInt,
    val artifactAbi: UInt,
    val artifactWriterVersion: UInt,
    val payloadHash: Hash256,
    val platformAbi: Hash256,
) {
    init {
        validateLockText("compiler version", compilerVersion)
        validateLockText("language version", languageVersion)
    }
}

data class ResolvedModule(
    val id: ModuleId,
    val major: ApiMajor,
    val version: String,
    val contentHash: Hash256,
) {
    init {
        validateLockText("module version", version)
    }
}

data class ResolvedAddon(
    val id: AddonId,
    val version: String,
    val contentHash: Hash256,
) {
    init {
        validateLockText("module version", version)
    }
}

class ProjectLock private constructor(
    val format: Int,
    val toolchain: ToolchainLockIdentity,
    addons: List<ResolvedAddon>,
) {
    val addons: List<ResolvedAddon> = Collections.unmodifiableList(addons.toList())

    override fun equals(other: Any?): Boolean =
        other is ProjectLock && format == other.format && toolchain == other.toolchain && addons == other.addons

    override fun hashCode(): Int = 31 * (31 * format + toolchain.hashCode()) + addons.hashCode()

    override fun toString(): String = "ProjectLock(format=$format, toolchain=$toolchain, addons=$addons)"

    companion object {
        const val FORMAT = 3

        fun of(
            toolchain: ToolchainLockIdentity,
            addons: List<ResolvedAddon>,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectLock {
            require(addons.size <= limits.addons) { "project addon count exceeds limit" }
            val copied = addons.sortedWith(RESOLVED_ADDON_COMPARATOR)
            require(copied.map(ResolvedAddon::id).toSet().size == copied.size) { "locked addon IDs must be unique" }
            return ProjectLock(FORMAT, toolchain, copied)
        }
    }
}

internal val RESOLVED_ADDON_COMPARATOR = Comparator<ResolvedAddon> { left, right -> left.id.compareTo(right.id) }

internal fun validateLockText(
    description: String,
    value: String,
) {
    val bytes =
        try {
            TomlSupport.strictUtf8(value)
        } catch (exception: Exception) {
            throw IllegalArgumentException("$description must be strict UTF-8", exception)
        }
    require(bytes.isNotEmpty()) { "$description must not be empty" }
    require(bytes.size <= 128) { "$description exceeds 128 UTF-8 bytes" }
    require(value.codePoints().noneMatch(Character::isISOControl)) { "$description cannot contain control characters" }
}
