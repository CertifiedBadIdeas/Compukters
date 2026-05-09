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

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings

internal class RuntimeHostBridge(
    private val runtime: DeviceRuntime,
) {
    suspend fun invoke(
        moduleName: String,
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue {
        ensureCapability(moduleName)
        return when (moduleName) {
            "filesystem" -> invokeFilesystem(functionName, arguments)
            "system" -> invokeSystem(functionName, arguments)
            "display" -> invokeDisplay(functionName, arguments)
            "runtime" -> invokeRuntime(functionName, arguments)
            "events" -> invokeEvents(functionName, arguments)
            "ipc" -> invokeIpc(functionName, arguments)
            "process" -> invokeProcess(functionName, arguments)
            "strings" -> invokeStrings(functionName, arguments)
            "monitor" -> invokeMonitor(functionName, arguments)
            else -> error("Unknown module $moduleName")
        }
    }

    fun fromEvent(event: VmEvent): VmValue.RecordValue {
        val (id, argCount) = runtime.events.capture(event.arguments)
        return VmValue.RecordValue(
            typeName = "Event",
            fields =
                mapOf(
                    "name" to VmValue.StringValue(event.name),
                    "id" to VmValue.IntValue(id),
                    "argCount" to VmValue.IntValue(argCount),
                ),
        )
    }

    private fun fromPoll(result: VmPollResult): VmValue.RecordValue =
        VmValue.RecordValue(
            typeName = "Poll",
            fields =
                mapOf(
                    "kind" to VmValue.StringValue(result.kind),
                    "text" to VmValue.StringValue(result.text),
                    "event" to fromEvent(result.event),
                ),
        )

    private suspend fun invokeFilesystem(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "exists" -> {
                VmValue.BoolValue(runtime.filesystem.exists(arguments[0].asString()))
            }

            "isDirectory" -> {
                VmValue.BoolValue(runtime.filesystem.isDirectory(arguments[0].asString()))
            }

            "readText" -> {
                VmValue.StringValue(runtime.filesystem.readText(arguments[0].asString()) ?: "")
            }

            "writeText" -> {
                runtime.filesystem.writeText(arguments[0].asString(), arguments[1].asString())
                VmValue.UnitValue
            }

            "makeDir" -> {
                VmValue.BoolValue(runtime.filesystem.makeDirectory(arguments[0].asString()))
            }

            "remove" -> {
                VmValue.BoolValue(runtime.filesystem.remove(arguments[0].asString()))
            }

            "list" -> {
                val path = arguments.singleOrNull()?.asString().orEmpty()
                VmValue.StringValue(formatWorkspaceListing(runtime.filesystem.list(path)))
            }

            else -> {
                error("Unknown filesystem function $functionName")
            }
        }

    private suspend fun invokeSystem(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "deviceId" -> {
                VmValue.IntValue(runtime.system.deviceId)
            }

            "currentTick" -> {
                VmValue.LongValue(runtime.system.currentTick)
            }

            "label" -> {
                VmValue.StringValue(runtime.system.label.orEmpty())
            }

            "profileName" -> {
                VmValue.StringValue(runtime.profile.displayName)
            }

            "log" -> {
                runtime.system.log(arguments[0].asString())
                VmValue.UnitValue
            }

            "shutdown" -> {
                runtime.system.shutdown()
                VmValue.UnitValue
            }

            "reboot" -> {
                runtime.system.reboot()
                VmValue.UnitValue
            }

            else -> {
                error("Unknown system function $functionName")
            }
        }

    private suspend fun invokeEvents(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue {
        val eventId = arguments.firstOrNull()?.eventId() ?: 0
        return when (functionName) {
            "pull" -> fromEvent(runtime.pullEvent(arguments.singleOrNull()?.asString()))
            "tryPull" -> fromEvent(runtime.tryPullEvent(arguments.singleOrNull()?.asString()) ?: VmEvent(""))
            "argCount" -> VmValue.IntValue(runtime.events.argCount(eventId))
            "argInt" -> VmValue.IntValue(runtime.events.argInt(eventId, arguments[1].asInt()))
            "argBool" -> VmValue.BoolValue(runtime.events.argBool(eventId, arguments[1].asInt()))
            "argString" -> VmValue.StringValue(runtime.events.argString(eventId, arguments[1].asInt()))
            else -> error("Unknown events function $functionName")
        }
    }

    private suspend fun invokeRuntime(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "poll" -> {
                measuredHostCallWait("runtime", "poll") {
                    fromPoll(runtime.poll(arguments[0].asInt()))
                }
            }

            else -> error("Unknown runtime function $functionName")
        }

    private suspend fun invokeIpc(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "open" -> {
                VmValue.IntValue(runtime.ipc.open())
            }

            "write" -> {
                runtime.ipc.write(arguments[0].asInt(), arguments[1].asString())
                VmValue.UnitValue
            }

            "read" -> {
                measuredHostCallWait("ipc", "read") {
                    readNativeIpc(arguments[0].asInt())?.let(VmValue::StringValue) ?:
                    VmValue.StringValue(runtime.ipc.read(arguments[0].asInt()))
                }
            }

            "tryRead" -> {
                VmValue.StringValue(runtime.ipc.tryRead(arguments[0].asInt()))
            }

            "close" -> {
                runtime.ipc.close(arguments[0].asInt())
                VmValue.UnitValue
            }

            else -> {
                error("Unknown ipc function $functionName")
            }
        }

    private suspend fun readNativeIpc(channel: Int): String? {
        val handle = (runtime as? NativeDeviceKernelProvider)?.nativeDeviceKernelHandle?.takeIf { it != 0L } ?: return null
        while (true) {
            val text = NativeVmBindings.tryReadDeviceIpc(handle, channel) ?: return null
            if (text.isNotEmpty()) {
                return text
            }
            runtime.yield()
        }
    }

    private suspend fun <T> measuredHostCallWait(
        moduleName: String,
        functionName: String,
        block: suspend () -> T,
    ): T {
        if (!runtime.metrics.collectsDetailedMetrics) {
            return block()
        }
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            runtime.metrics.recordVmHostCallWait(moduleName, functionName, System.nanoTime() - started)
        }
    }

    private fun invokeDisplay(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "primary" -> {
                VmValue.IntValue(runtime.display.primary())
            }

            "isAttached" -> {
                VmValue.BoolValue(runtime.display.isAttached(arguments[0].asInt()))
            }

            "width" -> {
                VmValue.IntValue(runtime.display.width(arguments[0].asInt()))
            }

            "height" -> {
                VmValue.IntValue(runtime.display.height(arguments[0].asInt()))
            }

            "clear" -> {
                runtime.display.clear(arguments[0].asInt(), arguments[1].asInt())
                VmValue.UnitValue
            }

            "setPixel" -> {
                runtime.display.setPixel(arguments[0].asInt(), arguments[1].asInt(), arguments[2].asInt(), arguments[3].asInt())
                VmValue.UnitValue
            }

            "fillRect" -> {
                runtime.display.fillRect(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                )
                VmValue.UnitValue
            }

            "copyRect" -> {
                runtime.display.copyRect(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                    arguments[6].asInt(),
                )
                VmValue.UnitValue
            }

            "blitMono" -> {
                runtime.display.blitMono(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asString(),
                    arguments[6].asInt(),
                    arguments[7].asInt(),
                )
                VmValue.UnitValue
            }

            "blitMono5x7" -> {
                runtime.display.blitMono5x7(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                    arguments[6].asInt(),
                    arguments[7].asInt(),
                    arguments[8].asInt(),
                    arguments[9].asInt(),
                    arguments[10].asInt(),
                    arguments[11].asInt(),
                )
                VmValue.UnitValue
            }

            "blitMono5x7Packed" -> {
                val glyph = arguments[3].asLong()
                runtime.display.blitMono5x7(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    ((glyph shr 30) and 0b11111).toInt(),
                    ((glyph shr 25) and 0b11111).toInt(),
                    ((glyph shr 20) and 0b11111).toInt(),
                    ((glyph shr 15) and 0b11111).toInt(),
                    ((glyph shr 10) and 0b11111).toInt(),
                    ((glyph shr 5) and 0b11111).toInt(),
                    (glyph and 0b11111).toInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                )
                VmValue.UnitValue
            }

            "blitMono5x7Text" -> {
                runtime.display.blitMono5x7Text(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asString(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                )
                VmValue.UnitValue
            }

            "present" -> {
                runtime.display.present(arguments[0].asInt())
                VmValue.UnitValue
            }

            else -> {
                error("Unknown display function $functionName")
            }
        }

    private suspend fun invokeProcess(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "currentDirectory" -> {
                VmValue.StringValue(runtime.process.workingDirectory)
            }

            "argument" -> {
                VmValue.StringValue(runtime.process.argument)
            }

            "changeDirectory" -> {
                VmValue.BoolValue(runtime.process.changeDirectory(arguments[0].asString()))
            }

            "run" -> {
                VmValue.IntValue(
                    when (arguments.size) {
                        1 -> runtime.process.run(arguments[0].asString())
                        2 -> runtime.process.run(arguments[0].asString(), arguments[1].asString())
                        else -> error("Unsupported process.run arity ${arguments.size}")
                    },
                )
            }

            "spawn" -> {
                VmValue.IntValue(
                    when (arguments.size) {
                        1 -> runtime.process.spawn(arguments[0].asString())
                        2 -> runtime.process.spawn(arguments[0].asString(), arguments[1].asString())
                        else -> error("Unsupported process.spawn arity ${arguments.size}")
                    },
                )
            }

            "wait" -> {
                VmValue.IntValue(runtime.process.wait(arguments[0].asInt()))
            }

            else -> {
                error("Unknown process function $functionName")
            }
        }

    private fun invokeStrings(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "trim" -> {
                VmValue.StringValue(arguments[0].asString().trim())
            }

            "beforeSpace" -> {
                VmValue.StringValue(arguments[0].asString().substringBeforeFirstSpace())
            }

            "afterSpace" -> {
                VmValue.StringValue(arguments[0].asString().substringAfterFirstSpace())
            }

            "isBlank" -> {
                VmValue.BoolValue(arguments[0].asString().isBlank())
            }

            "toInt" -> {
                VmValue.IntValue(arguments[0].asString().trim().toIntOrNull() ?: 0)
            }

            "length" -> {
                VmValue.IntValue(arguments[0].asString().length)
            }

            "charAt" -> {
                val text = arguments[0].asString()
                val index = arguments[1].asInt()
                VmValue.StringValue(if (index in text.indices) text[index].toString() else "")
            }

            "charCodeAt" -> {
                val text = arguments[0].asString()
                val index = arguments[1].asInt()
                VmValue.IntValue(if (index in text.indices) text[index].code else -1)
            }

            "repeat" -> {
                val text = arguments[0].asString()
                val count = arguments[1].asInt().coerceAtLeast(0)
                VmValue.StringValue(text.repeat(count))
            }

            "slice" -> {
                val text = arguments[0].asString()
                val start = arguments[1].asInt().coerceIn(0, text.length)
                val end = arguments[2].asInt().coerceIn(start, text.length)
                VmValue.StringValue(text.substring(start, end))
            }

            "replaceRange" -> {
                val text = arguments[0].asString()
                val start = arguments[1].asInt().coerceIn(0, text.length)
                val replacement = arguments[2].asString()
                val end = (start + replacement.length).coerceAtMost(text.length)
                VmValue.StringValue(text.substring(0, start) + replacement + text.substring(end))
            }

            else -> {
                error("Unknown strings function $functionName")
            }
        }

    private fun VmValue.eventId(): Int =
        when (this) {
            is VmValue.RecordValue -> fields["id"]?.asInt() ?: 0
            else -> 0
        }

    private fun invokeMonitor(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "exists" -> VmValue.BoolValue(runtime.peripherals.monitorExists())
            else -> error("Unknown monitor function $functionName")
        }

    private fun ensureCapability(moduleName: String) {
        val capability =
            when (moduleName) {
                "filesystem" -> DeviceCapability.FILESYSTEM
                "system" -> DeviceCapability.SYSTEM
                "display" -> DeviceCapability.DISPLAY
                "events" -> DeviceCapability.EVENTS
                "ipc" -> DeviceCapability.IPC
                "process" -> DeviceCapability.SYSTEM
                "monitor" -> DeviceCapability.PERIPHERALS
                else -> null
            }
        if (capability != null && capability !in runtime.profile.allowedCapabilities) {
            error("Capability $moduleName is not allowed for this computer profile.")
        }
    }
}
