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

package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmRunner

object VmRunnerFactory {
    private const val RUNNER_PROPERTY = "ckl.vm.runner"
    private const val NATIVE_LIBRARY_PROPERTY = "ckl.vm.native.library"

    fun fromSystemProperties(): VmRunner =
        when (val runner = System.getProperty(RUNNER_PROPERTY)?.trim()?.lowercase().orEmpty()) {
            "", "kotlin" -> KotlinVmRunner
            "rust", "native" ->
                NativeVmRunner.fromSystemProperty()
                    ?: error("Rust VM runner requires -D$NATIVE_LIBRARY_PROPERTY=/absolute/path/to/libckl_vm.so")
            else -> error("Unsupported CKL VM runner '$runner'; expected 'kotlin' or 'rust'")
        }
}
