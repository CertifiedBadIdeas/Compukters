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

object CkVmHostImportRegistry {
    val all: List<CkVmHostImport> =
        listOf(
            descriptor(1000, "display", "primary", emptyList(), "Int"),
            descriptor(1001, "display", "isAttached", listOf("Int"), "Bool"),
            descriptor(1002, "display", "width", listOf("Int"), "Int"),
            descriptor(1003, "display", "height", listOf("Int"), "Int"),
            descriptor(1004, "display", "clear", listOf("Int", "Int"), "Unit"),
            descriptor(1005, "display", "setPixel", listOf("Int", "Int", "Int", "Int"), "Unit"),
            descriptor(1006, "display", "fillRect", listOf("Int", "Int", "Int", "Int", "Int", "Int"), "Unit"),
            descriptor(1007, "display", "copyRect", listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int"), "Unit"),
            descriptor(1008, "display", "blitMono", listOf("Int", "Int", "Int", "Int", "Int", "String", "Int", "Int"), "Unit"),
            descriptor(
                1009,
                "display",
                "blitMono5x7",
                listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int"),
                "Unit",
            ),
            descriptor(1010, "display", "blitMono5x7Packed", listOf("Int", "Int", "Int", "Long", "Int", "Int"), "Unit"),
            descriptor(1011, "display", "present", listOf("Int"), "Unit"),
            descriptor(1012, "display", "blitMono5x7Text", listOf("Int", "Int", "Int", "String", "Int", "Int"), "Unit"),
            descriptor(2000, "filesystem", "exists", listOf("String"), "Bool"),
            descriptor(2001, "filesystem", "readText", listOf("String"), "String"),
            descriptor(2002, "filesystem", "isDirectory", listOf("String"), "Bool"),
            descriptor(2003, "filesystem", "writeText", listOf("String", "String"), "Unit"),
            descriptor(2004, "filesystem", "makeDir", listOf("String"), "Bool"),
            descriptor(2005, "filesystem", "remove", listOf("String"), "Bool"),
            descriptor(2006, "filesystem", "list", emptyList(), "String"),
            descriptor(2007, "filesystem", "list", listOf("String"), "String"),
            descriptor(3000, "system", "deviceId", emptyList(), "Int"),
            descriptor(3001, "system", "currentTick", emptyList(), "Long"),
            descriptor(3002, "system", "label", emptyList(), "String"),
            descriptor(3003, "system", "profileName", emptyList(), "String"),
            descriptor(3004, "system", "log", listOf("String"), "Unit"),
            descriptor(3005, "system", "shutdown", emptyList(), "Unit"),
            descriptor(3006, "system", "reboot", emptyList(), "Unit"),
            descriptor(4000, "events", "pull", emptyList(), "Event"),
            descriptor(4001, "events", "pull", listOf("String"), "Event"),
            descriptor(4002, "events", "tryPull", emptyList(), "Event"),
            descriptor(4003, "events", "tryPull", listOf("String"), "Event"),
            descriptor(4004, "events", "argCount", listOf("Event"), "Int"),
            descriptor(4005, "events", "argInt", listOf("Event", "Int"), "Int"),
            descriptor(4006, "events", "argBool", listOf("Event", "Int"), "Bool"),
            descriptor(4007, "events", "argString", listOf("Event", "Int"), "String"),
            descriptor(5000, "ipc", "open", emptyList(), "Int"),
            descriptor(5001, "ipc", "write", listOf("Int", "String"), "Unit"),
            descriptor(5002, "ipc", "read", listOf("Int"), "String"),
            descriptor(5003, "ipc", "tryRead", listOf("Int"), "String"),
            descriptor(5004, "ipc", "close", listOf("Int"), "Unit"),
            descriptor(6000, "process", "currentDirectory", emptyList(), "String"),
            descriptor(6001, "process", "argument", emptyList(), "String"),
            descriptor(6002, "process", "changeDirectory", listOf("String"), "Bool"),
            descriptor(6003, "process", "run", listOf("String"), "Int"),
            descriptor(6004, "process", "run", listOf("String", "String"), "Int"),
            descriptor(6005, "process", "spawn", listOf("String"), "Int"),
            descriptor(6006, "process", "spawn", listOf("String", "String"), "Int"),
            descriptor(6007, "process", "wait", listOf("Int"), "Int"),
            descriptor(7000, "strings", "trim", listOf("String"), "String"),
            descriptor(7001, "strings", "beforeSpace", listOf("String"), "String"),
            descriptor(7002, "strings", "afterSpace", listOf("String"), "String"),
            descriptor(7003, "strings", "isBlank", listOf("String"), "Bool"),
            descriptor(7004, "strings", "toInt", listOf("String"), "Int"),
            descriptor(7005, "strings", "length", listOf("String"), "Int"),
            descriptor(7006, "strings", "charAt", listOf("String", "Int"), "String"),
            descriptor(7007, "strings", "repeat", listOf("String", "Int"), "String"),
            descriptor(7008, "strings", "slice", listOf("String", "Int", "Int"), "String"),
            descriptor(7009, "strings", "replaceRange", listOf("String", "Int", "String"), "String"),
            descriptor(7010, "strings", "charCodeAt", listOf("String", "Int"), "Int"),
            descriptor(8000, "runtime", "poll", listOf("Int"), "Poll"),
        ).also(::validate)

    private val bySignature: Map<Key, CkVmHostImport> =
        all.associateBy { import -> Key(import.moduleName, import.functionName, import.parameterTypes.size) }

    fun find(
        moduleName: String,
        functionName: String,
        argumentCount: Int,
    ): CkVmHostImport? = bySignature[Key(moduleName, functionName, argumentCount)]

    fun require(
        moduleName: String,
        functionName: String,
        argumentCount: Int,
    ): CkVmHostImport =
        find(moduleName, functionName, argumentCount)
            ?: throw UnsupportedOperationException(
                "CkVmImage backend does not support host import $moduleName::$functionName/$argumentCount",
            )

    private data class Key(
        val moduleName: String,
        val functionName: String,
        val argumentCount: Int,
    )

    private fun descriptor(
        id: Int,
        moduleName: String,
        functionName: String,
        parameterTypes: List<String>,
        returnType: String,
    ): CkVmHostImport = CkVmHostImport(id, moduleName, functionName, parameterTypes, returnType)

    private fun validate(imports: List<CkVmHostImport>) {
        val duplicateIds = imports.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate CKVM host import ids: $duplicateIds" }
        val duplicateSignatures =
            imports
                .groupBy {
                    Key(
                        it.moduleName,
                        it.functionName,
                        it.parameterTypes.size,
                    )
                }.filterValues { it.size > 1 }
                .keys
        require(duplicateSignatures.isEmpty()) { "Duplicate CKVM host import signatures: $duplicateSignatures" }
    }
}
