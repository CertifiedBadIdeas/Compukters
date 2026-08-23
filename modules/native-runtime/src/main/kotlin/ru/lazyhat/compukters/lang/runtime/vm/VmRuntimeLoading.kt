/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.file.Path

sealed interface VmRuntimeLoadSource {
    data class ExplicitPath(
        val path: Path,
    ) : VmRuntimeLoadSource

    data class PackagedResource(
        val resourcePath: String,
    ) : VmRuntimeLoadSource
}

sealed interface VmRuntimeLoadFailure {
    data class UnsupportedPlatform(
        val osName: String,
        val osArch: String,
    ) : VmRuntimeLoadFailure

    data class InvalidExplicitPath(
        val path: String,
        val detail: String,
    ) : VmRuntimeLoadFailure

    data class MissingResource(
        val resourcePath: String,
    ) : VmRuntimeLoadFailure

    data class ResourceExtraction(
        val resourcePath: String,
        val detail: String,
    ) : VmRuntimeLoadFailure

    data class NativeLink(
        val source: String,
        val detail: String,
    ) : VmRuntimeLoadFailure
}

sealed interface VmRuntimeLoadResult {
    data class Loaded(
        val source: VmRuntimeLoadSource,
    ) : VmRuntimeLoadResult

    data class Failed(
        val failure: VmRuntimeLoadFailure,
    ) : VmRuntimeLoadResult
}

class VmRuntimeLoadException(
    val failure: VmRuntimeLoadFailure,
) : IllegalStateException("Compukter JNI runtime load failed: $failure")
