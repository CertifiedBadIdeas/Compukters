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

package ru.lazyhat.compukterkraft.lang.frontend

import ru.lazyhat.compukterkraft.lang.api.BuiltinFunction
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.BuiltinType
import ru.lazyhat.compukterkraft.lang.api.ModuleOrigin
import ru.lazyhat.compukterkraft.lang.api.RecordFieldDefinition

object LanguageBuiltins {
    val defaultRuntimeRegistry =
        BuiltinRegistry(
            modules =
                listOf(
                    BuiltinModule(
                        name = "terminal",
                        documentation = "Terminal I/O operations.",
                        origin = ModuleOrigin.BASE_VM,
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
                                BuiltinFunction("readLine", emptyList(), "String", "Reads a line from terminal input."),
                                BuiltinFunction(
                                    "readLine",
                                    listOf("String"),
                                    "String",
                                    "Prints a prompt and reads a line from terminal input.",
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
                        name = "stdout",
                        documentation = "Raw byte stream output. Writes are interpreted as a VT-100 subset " +
                            "by the attached terminal(s).",
                        origin = ModuleOrigin.BASE_VM,
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "write",
                                    listOf("String"),
                                    "Unit",
                                    "Appends text to the computer's stdout stream.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "filesystem",
                        documentation = "Sandboxed filesystem access through the computer workspace.",
                        origin = ModuleOrigin.BASE_VM,
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
                                    "isDirectory",
                                    listOf("String"),
                                    "Bool",
                                    "Returns true when a workspace path points at a directory.",
                                ),
                                BuiltinFunction(
                                    "writeText",
                                    listOf("String", "String"),
                                    "Unit",
                                    "Writes a text file in the workspace.",
                                ),
                                BuiltinFunction(
                                    "makeDir",
                                    listOf("String"),
                                    "Bool",
                                    "Creates a workspace directory and returns true when it succeeds.",
                                ),
                                BuiltinFunction(
                                    "remove",
                                    listOf("String"),
                                    "Bool",
                                    "Removes a workspace file or empty directory.",
                                ),
                                BuiltinFunction(
                                    "list",
                                    emptyList(),
                                    "String",
                                    "Lists the current directory as a space-separated string.",
                                ),
                                BuiltinFunction(
                                    "list",
                                    listOf("String"),
                                    "String",
                                    "Lists a directory as a space-separated string.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "system",
                        documentation = "System introspection and lifecycle controls.",
                        origin = ModuleOrigin.BASE_VM,
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
                        origin = ModuleOrigin.BASE_VM,
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
                    BuiltinModule(
                        name = "process",
                        documentation = "Current process information and program execution.",
                        origin = ModuleOrigin.BASE_VM,
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "currentDirectory",
                                    emptyList(),
                                    "String",
                                    "Returns the current working directory.",
                                ),
                                BuiltinFunction(
                                    "argument",
                                    emptyList(),
                                    "String",
                                    "Returns the raw argument passed to the current program.",
                                ),
                                BuiltinFunction(
                                    "changeDirectory",
                                    listOf("String"),
                                    "Bool",
                                    "Changes the current working directory.",
                                ),
                                BuiltinFunction(
                                    "run",
                                    listOf("String"),
                                    "Int",
                                    "Runs another program and returns its exit status.",
                                ),
                                BuiltinFunction(
                                    "run",
                                    listOf("String", "String"),
                                    "Int",
                                    "Runs another program with a raw argument string and returns its exit status.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "strings",
                        documentation = "Simple string helpers for shell-style programs.",
                        origin = ModuleOrigin.BASE_VM,
                        functions =
                            listOf(
                                BuiltinFunction("trim", listOf("String"), "String", "Trims leading and trailing whitespace."),
                                BuiltinFunction(
                                    "beforeSpace",
                                    listOf("String"),
                                    "String",
                                    "Returns everything before the first whitespace character.",
                                ),
                                BuiltinFunction(
                                    "afterSpace",
                                    listOf("String"),
                                    "String",
                                    "Returns everything after the first whitespace character.",
                                ),
                                BuiltinFunction("isBlank", listOf("String"), "Bool", "Returns true when the string is blank."),
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

    @Deprecated("Use defaultRuntimeRegistry")
    val registry: BuiltinRegistry
        get() = defaultRuntimeRegistry
}
