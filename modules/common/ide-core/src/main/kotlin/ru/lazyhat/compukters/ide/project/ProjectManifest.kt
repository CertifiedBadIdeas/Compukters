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

import java.util.Collections
import java.util.TreeSet

class ProjectManifest private constructor(
    val format: Int,
    val name: String,
    addons: Set<AddonId>,
) {
    val addons: Set<AddonId> =
        Collections.unmodifiableSet(TreeSet<AddonId>().apply { addAll(addons) })

    override fun equals(other: Any?): Boolean =
        other is ProjectManifest && format == other.format && name == other.name && addons == other.addons

    override fun hashCode(): Int = 31 * (31 * format + name.hashCode()) + addons.hashCode()

    override fun toString(): String = "ProjectManifest(format=$format, name=$name, addons=$addons)"

    companion object {
        const val FORMAT = 3

        fun of(
            name: String,
            addons: Set<AddonId>,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectManifest {
            validateName(name, limits)
            require(addons.size <= limits.addons) { "project addon count exceeds limit" }
            return ProjectManifest(FORMAT, name, addons.toSet())
        }

        internal fun validateName(
            name: String,
            limits: ProjectLimits,
        ) {
            val bytes =
                try {
                    TomlSupport.strictUtf8(name)
                } catch (exception: Exception) {
                    throw IllegalArgumentException("project name must be strict UTF-8", exception)
                }
            require(name.codePointCount(0, name.length) in 1..limits.projectNameCodePoints) {
                "project name code-point count exceeds limit"
            }
            require(bytes.size <= limits.projectNameUtf8Bytes) { "project name byte count exceeds limit" }
            require(name != "." && name != "..") { "project name cannot be a relative path marker" }
            require('/' !in name && '\\' !in name) { "project name cannot contain path separators" }
            require(name.codePoints().noneMatch(Character::isISOControl)) { "project name cannot contain control characters" }
        }
    }
}
