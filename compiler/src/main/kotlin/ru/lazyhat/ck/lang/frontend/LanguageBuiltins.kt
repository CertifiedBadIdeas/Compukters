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

package ru.lazyhat.ck.lang.frontend

import ru.lazyhat.ck.lang.api.BuiltinFunction
import ru.lazyhat.ck.lang.api.BuiltinModule
import ru.lazyhat.ck.lang.api.BuiltinRegistry
import ru.lazyhat.ck.lang.api.BuiltinType
import ru.lazyhat.ck.lang.api.RecordFieldDefinition

object LanguageBuiltins {
    val registry =
        BuiltinRegistry(
            modules =
                listOf(
                    BuiltinModule(
                        name = "terminal",
                        documentation = "Terminal I/O operations.",
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "write",
                                    listOf("String"),
                                    "Unit",
                                    "Writes text without advancing the cursor.",
                                ),
                                BuiltinFunction(
                                    "printLine",
                                    listOf("String"),
                                    "Unit",
                                    "Writes text and moves to the next line.",
                                ),
                                BuiltinFunction("clear", emptyList(), "Unit", "Clears the terminal contents."),
                                BuiltinFunction(
                                    "setCursor",
                                    listOf("Int", "Int"),
                                    "Unit",
                                    "Moves the terminal cursor.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "filesystem",
                        documentation = "Sandboxed filesystem access through the computer workspace.",
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "exists",
                                    listOf("String"),
                                    "Bool",
                                    "Returns true when a workspace path exists.",
                                ),
                                BuiltinFunction(
                                    "readText",
                                    listOf("String"),
                                    "String",
                                    "Reads a text file from the workspace.",
                                ),
                                BuiltinFunction(
                                    "writeText",
                                    listOf("String", "String"),
                                    "Unit",
                                    "Writes a text file in the workspace.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "system",
                        documentation = "System introspection and lifecycle controls.",
                        functions =
                            listOf(
                                BuiltinFunction("computerId", emptyList(), "Int", "Returns the current computer id."),
                                BuiltinFunction("currentTick", emptyList(), "Long", "Returns the current server tick."),
                                BuiltinFunction(
                                    "label",
                                    emptyList(),
                                    "String",
                                    "Returns the computer label or an empty string.",
                                ),
                                BuiltinFunction(
                                    "profileName",
                                    emptyList(),
                                    "String",
                                    "Returns the current computer profile name.",
                                ),
                                BuiltinFunction("log", listOf("String"), "Unit", "Writes a line to the server log."),
                                BuiltinFunction("shutdown", emptyList(), "Unit", "Stops the current computer."),
                                BuiltinFunction("reboot", emptyList(), "Unit", "Requests a reboot."),
                            ),
                    ),
                    BuiltinModule(
                        name = "events",
                        documentation = "Event queue access.",
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "pull",
                                    emptyList(),
                                    "Event",
                                    "Waits until the next event is available.",
                                ),
                                BuiltinFunction(
                                    "pull",
                                    listOf("String"),
                                    "Event",
                                    "Waits for an event with the requested name.",
                                ),
                            ),
                    ),
                ),
            globals =
                listOf(
                    BuiltinFunction(
                        "yield",
                        emptyList(),
                        "Unit",
                        "Voluntarily yields execution back to the scheduler.",
                    ),
                    BuiltinFunction(
                        "sleep",
                        listOf("Long"),
                        "Unit",
                        "Suspends the current program for the specified number of ticks.",
                    ),
                ),
            builtinTypes =
                listOf(
                    BuiltinType("Unit", "The absence of a value."),
                    BuiltinType("Bool", "Boolean true/false values."),
                    BuiltinType("Int", "32-bit signed integer."),
                    BuiltinType("Long", "64-bit signed integer."),
                    BuiltinType("String", "UTF-8 string."),
                    BuiltinType(
                        name = "Event",
                        documentation = "An event pulled from the runtime queue.",
                        fields = listOf(RecordFieldDefinition("name", "String", "The event name.")),
                    ),
                ),
        )
}
