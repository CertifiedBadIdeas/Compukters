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
            "terminal" -> invokeTerminal(functionName, arguments)
            "stdout" -> invokeStdout(functionName, arguments)
            "process" -> invokeProcess(functionName, arguments)
            "strings" -> invokeStrings(functionName, arguments)
            "monitor" -> invokeMonitor(functionName, arguments)
            else -> error("Unknown module $moduleName")
        }
    }

    fun fromEvent(event: VmEvent): VmValue.RecordValue =
        VmValue.RecordValue(
            typeName = "Event",
            fields = mapOf("name" to VmValue.StringValue(event.name)),
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
            "computerId" -> {
                VmValue.IntValue(runtime.system.computerId)
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

    private suspend fun invokeTerminal(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "write" -> {
                runtime.terminal.write(arguments[0].asString())
                VmValue.UnitValue
            }

            "printLine" -> {
                runtime.terminal.printLine(arguments[0].asString())
                VmValue.UnitValue
            }

            "readLine" -> {
                VmValue.StringValue(runtime.terminal.readLine(arguments.singleOrNull()?.asString().orEmpty()))
            }

            "clear" -> {
                runtime.terminal.clear()
                VmValue.UnitValue
            }

            "setCursor" -> {
                runtime.terminal.setCursor(arguments[0].asInt(), arguments[1].asInt())
                VmValue.UnitValue
            }

            else -> {
                error("Unknown terminal function $functionName")
            }
        }

    private fun invokeStdout(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "write" -> {
                runtime.stdio.writeString(arguments[0].asString())
                VmValue.UnitValue
            }

            else -> {
                error("Unknown stdout function $functionName")
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

            else -> {
                error("Unknown process function $functionName")
            }
        }

    private fun invokeStrings(
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue =
        when (functionName) {
            "trim" -> VmValue.StringValue(arguments[0].asString().trim())
            "beforeSpace" -> VmValue.StringValue(arguments[0].asString().substringBeforeFirstSpace())
            "afterSpace" -> VmValue.StringValue(arguments[0].asString().substringAfterFirstSpace())
            "isBlank" -> VmValue.BoolValue(arguments[0].asString().isBlank())
            else -> error("Unknown strings function $functionName")
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
                "terminal" -> DeviceCapability.TERMINAL
                "stdout" -> DeviceCapability.TERMINAL
                "events" -> DeviceCapability.EVENTS
                "process" -> DeviceCapability.SYSTEM
                "monitor" -> DeviceCapability.PERIPHERALS
                else -> null
            }
        if (capability != null && capability !in runtime.profile.allowedCapabilities) {
            error("Capability $moduleName is not allowed for this computer profile.")
        }
    }
}
