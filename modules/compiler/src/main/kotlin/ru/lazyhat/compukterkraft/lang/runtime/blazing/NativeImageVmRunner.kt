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

import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.RuntimeHostBridge
import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi

interface NativeImageRuntimeRunner {
    suspend fun run(
        image: CkVmImage,
        runtime: DeviceRuntime,
    )
}

class NativeImageVmRunner private constructor(
    private val libraryPath: String,
) : NativeImageRuntimeRunner {
    override suspend fun run(
        image: CkVmImage,
        runtime: DeviceRuntime,
    ) {
        val imageBytes = CkVmImageAbi.encode(image)
        val bridge = RuntimeHostBridge(runtime)
        val handle = NativeVmBindings.createImage(libraryPath, imageBytes, runtime.profile.resources.cpu.instructionsPerSlice)
        try {
            while (true) {
                val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))
                if (signal !is NativeVmSignal.Error) {
                    runtime.metrics.recordVmSignal(signal.kind)
                }
                when (signal) {
                    is NativeVmSignal.Halt -> {
                        return
                    }

                    is NativeVmSignal.Error -> {
                        error("Native image VM failed for device ${runtime.system.deviceId}: ${signal.message}")
                    }

                    NativeVmSignal.Pause -> {
                        runtime.yield()
                    }

                    NativeVmSignal.Yield -> {
                        runtime.yield()
                        NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("", "yield"))
                    }

                    is NativeVmSignal.Sleep -> {
                        runtime.sleep(signal.ticks)
                        NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("", "sleep"))
                    }

                    is NativeVmSignal.WaitEvent -> {
                        val event = runtime.pullEvent(signal.filter)
                        NativeVmBindings.resumeImageWith(handle, bridge.fromEvent(event).toNativeBytes("events", "pull"))
                    }

                    is NativeVmSignal.HostCall -> {
                        val result = invokeHostCall(runtime, bridge, signal)
                        NativeVmBindings.resumeImageWith(handle, result.toNativeBytes(signal.moduleName, signal.functionName))
                    }
                }
            }
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    private suspend fun invokeHostCall(
        runtime: DeviceRuntime,
        bridge: RuntimeHostBridge,
        signal: NativeVmSignal.HostCall,
    ): VmValue {
        val arguments = signal.arguments.map { it.toVmValue(signal.moduleName, signal.functionName) }
        if (!runtime.metrics.collectsDetailedMetrics) {
            return bridge.invoke(signal.moduleName, signal.functionName, arguments)
        }
        val started = System.nanoTime()
        try {
            return bridge.invoke(signal.moduleName, signal.functionName, arguments)
        } finally {
            runtime.metrics.recordVmHostCall(signal.moduleName, signal.functionName, System.nanoTime() - started)
        }
    }

    companion object {
        fun isAvailable(libraryPath: String?): Boolean = !libraryPath.isNullOrBlank()

        fun fromLibraryPath(libraryPath: String): NativeImageVmRunner = NativeImageVmRunner(libraryPath)

        fun fromSystemProperty(): NativeImageVmRunner? {
            val path = System.getProperty("ckl.vm.native.library")
            return if (isAvailable(path)) fromLibraryPath(requireNotNull(path)) else null
        }
    }
}

private val NativeVmSignal.kind: VmSignalKind
    get() =
        when (this) {
            is NativeVmSignal.Halt -> VmSignalKind.HALT
            NativeVmSignal.Pause -> VmSignalKind.PAUSE
            NativeVmSignal.Yield -> VmSignalKind.YIELD
            is NativeVmSignal.Sleep -> VmSignalKind.SLEEP
            is NativeVmSignal.WaitEvent -> VmSignalKind.WAIT_EVENT
            is NativeVmSignal.HostCall -> VmSignalKind.HOST_CALL
            is NativeVmSignal.Error -> error("Native image VM errors are not runtime VM signals")
        }
