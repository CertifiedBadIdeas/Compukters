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

@JvmInline
value class AddonId(
    val value: String,
) : Comparable<AddonId> {
    init {
        require(COMPONENT.matches(value)) { "invalid addon ID: $value" }
    }

    override fun compareTo(other: AddonId): Int = TomlSupport.utf8Comparator.compare(value, other.value)

    override fun toString(): String = value

    private companion object {
        val COMPONENT = Regex("[a-z][a-z0-9_-]{0,63}")
    }
}
