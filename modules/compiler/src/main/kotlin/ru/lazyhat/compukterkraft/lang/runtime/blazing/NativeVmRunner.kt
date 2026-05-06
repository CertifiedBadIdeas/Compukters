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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.VmRunner
import ru.lazyhat.compukterkraft.lang.runtime.abi.BytecodeAbi

class NativeVmRunner private constructor(
    private val libraryPath: String,
) : VmRunner {
    override suspend fun run(
        module: BytecodeModule,
        runtime: DeviceRuntime,
    ) {
        val bytecode = BytecodeAbi.encode(module)
        when (val signal = NativeVmSignal.decode(NativeVmBindings.runUntilSignal(libraryPath, bytecode, runtime.profile.resources.cpu.instructionsPerSlice))) {
            is NativeVmSignal.Halt -> return
            is NativeVmSignal.Error -> error("Native VM failed for device ${runtime.system.deviceId}: ${signal.message}")
            NativeVmSignal.Pause -> unsupported(signal)
            NativeVmSignal.Yield -> unsupported(signal)
            is NativeVmSignal.Sleep -> unsupported(signal)
            is NativeVmSignal.HostCall -> unsupported(signal)
        }
    }

    private fun unsupported(signal: NativeVmSignal): Nothing =
        throw UnsupportedOperationException(
            "Native VM signal $signal is not supported by the JNI prototype yet; " +
                "host-call resume and persistent native VM state are not implemented",
        )

    companion object {
        fun isAvailable(libraryPath: String?): Boolean = !libraryPath.isNullOrBlank()

        fun fromLibraryPath(libraryPath: String): NativeVmRunner = NativeVmRunner(libraryPath)

        fun fromSystemProperty(): NativeVmRunner? {
            val path = System.getProperty("ckl.vm.native.library")
            return if (isAvailable(path)) fromLibraryPath(requireNotNull(path)) else null
        }
    }
}
