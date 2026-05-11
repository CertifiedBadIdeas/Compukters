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

package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.runtime.DeviceProgram
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageRuntimeRunner
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunner

class CkVmImageComputerProgram(
    private val image: CkVmImage,
    private val runnerFactory: () -> NativeImageRuntimeRunner = {
        NativeImageVmRunner.fromDefaultLibrary()
            ?: error(
                "Rust image VM runner requires -Dckl.vm.native.library=/absolute/path/to/<platform library> " +
                    "or a bundled native VM library resource",
            )
    },
) : DeviceProgram {
    override suspend fun run(runtime: DeviceRuntime) {
        runnerFactory().run(image, runtime)
    }
}
