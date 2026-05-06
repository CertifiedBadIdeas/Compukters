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
                        name = "display",
                        documentation = "Framebuffer display operations. The attached display endpoint supplies resolution.",
                        origin = ModuleOrigin.BASE_VM,
                        functions =
                            listOf(
                                BuiltinFunction(
                                    "primary",
                                    emptyList(),
                                    "Int",
                                    "Returns the primary display id or -1 when no display is attached.",
                                ),
                                BuiltinFunction(
                                    "isAttached",
                                    listOf("Int"),
                                    "Bool",
                                    "Returns true when the display id is attached.",
                                ),
                                BuiltinFunction(
                                    "width",
                                    listOf("Int"),
                                    "Int",
                                    "Returns display width in pixels or 0 when missing.",
                                ),
                                BuiltinFunction(
                                    "height",
                                    listOf("Int"),
                                    "Int",
                                    "Returns display height in pixels or 0 when missing.",
                                ),
                                BuiltinFunction(
                                    "clear",
                                    listOf("Int", "Int"),
                                    "Unit",
                                    "Clears the display back buffer to an RGB565 color.",
                                ),
                                BuiltinFunction(
                                    "setPixel",
                                    listOf("Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Writes one RGB565 pixel.",
                                ),
                                BuiltinFunction(
                                    "fillRect",
                                    listOf("Int", "Int", "Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Fills a rectangle with an RGB565 color.",
                                ),
                                BuiltinFunction(
                                    "copyRect",
                                    listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Copies a rectangle inside the display back buffer.",
                                ),
                                BuiltinFunction(
                                    "blitMono",
                                    listOf("Int", "Int", "Int", "Int", "Int", "String", "Int", "Int"),
                                    "Unit",
                                    "Draws a row-major monochrome bitmap mask.",
                                ),
                                BuiltinFunction(
                                    "blitMono5x7",
                                    listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Draws a fixed 5x7 monochrome bitmap from seven numeric row masks.",
                                ),
                                BuiltinFunction(
                                    "present",
                                    listOf("Int"),
                                    "Unit",
                                    "Publishes changed pixels for a display.",
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
                                BuiltinFunction("deviceId", emptyList(), "Int", "Returns the current computer id."),
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
                                BuiltinFunction(
                                    "tryPull",
                                    emptyList(),
                                    "Event",
                                    "Returns the next event or an empty-name event when none is available.",
                                ),
                                BuiltinFunction(
                                    "tryPull",
                                    listOf("String"),
                                    "Event",
                                    "Returns a matching event or an empty-name event without blocking.",
                                ),
                                BuiltinFunction(
                                    "argCount",
                                    listOf("Event"),
                                    "Int",
                                    "Returns the number of low-level event payload arguments.",
                                ),
                                BuiltinFunction(
                                    "argInt",
                                    listOf("Event", "Int"),
                                    "Int",
                                    "Returns an event payload argument as Int or 0 when unavailable.",
                                ),
                                BuiltinFunction(
                                    "argBool",
                                    listOf("Event", "Int"),
                                    "Bool",
                                    "Returns an event payload argument as Bool or false when unavailable.",
                                ),
                                BuiltinFunction(
                                    "argString",
                                    listOf("Event", "Int"),
                                    "String",
                                    "Returns an event payload argument as String or an empty string when unavailable.",
                                ),
                            ),
                    ),
                    BuiltinModule(
                        name = "ipc",
                        documentation = "Low-level in-VM IPC channels.",
                        origin = ModuleOrigin.BASE_VM,
                        functions =
                            listOf(
                                BuiltinFunction("open", emptyList(), "Int", "Creates an IPC channel."),
                                BuiltinFunction("write", listOf("Int", "String"), "Unit", "Writes text to a channel."),
                                BuiltinFunction("read", listOf("Int"), "String", "Blocks until channel text is available."),
                                BuiltinFunction(
                                    "tryRead",
                                    listOf("Int"),
                                    "String",
                                    "Returns available channel text or an empty string.",
                                ),
                                BuiltinFunction("close", listOf("Int"), "Unit", "Closes a channel."),
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
                                BuiltinFunction(
                                    "spawn",
                                    listOf("String"),
                                    "Int",
                                    "Starts another program and returns its process id.",
                                ),
                                BuiltinFunction(
                                    "spawn",
                                    listOf("String", "String"),
                                    "Int",
                                    "Starts another program with a raw argument string and returns its process id.",
                                ),
                                BuiltinFunction(
                                    "wait",
                                    listOf("Int"),
                                    "Int",
                                    "Waits for a child process and returns its exit status.",
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
                                BuiltinFunction("toInt", listOf("String"), "Int", "Parses a decimal integer or returns 0."),
                                BuiltinFunction("length", listOf("String"), "Int", "Returns the string length in UTF-16 code units."),
                                BuiltinFunction("charAt", listOf("String", "Int"), "String", "Returns one character or an empty string."),
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
                    BuiltinType("Array", "Mutable fixed-size indexed collection.", typeParameterCount = 1),
                    BuiltinType("List", "Mutable growable indexed collection.", typeParameterCount = 1),
                    BuiltinType("Map", "Mutable insertion-ordered key/value collection.", typeParameterCount = 2),
                    BuiltinType(
                        name = "Event",
                        documentation = "An event pulled from the runtime queue.",
                        fields =
                            listOf(
                                RecordFieldDefinition("name", "String", "The event name."),
                                RecordFieldDefinition("id", "Int", "Runtime-local event payload id."),
                                RecordFieldDefinition("argCount", "Int", "Number of low-level event payload arguments."),
                            ),
                    ),
                ),
        )

    @Deprecated("Use defaultRuntimeRegistry")
    val registry: BuiltinRegistry
        get() = defaultRuntimeRegistry
}
