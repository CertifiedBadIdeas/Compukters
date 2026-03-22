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

package ru.lazyhat.compukterkraft.scripting.runtime

import ru.lazyhat.compukterkraft.MOD_ID
import java.io.File

object ScriptingPaths {
    const val SCRIPTING_JAR = "CompukterKraftCompiler.jar"
    const val SCRIPTING_LIBRARIES_DIRECTORY = "scripting-libs"

    fun rootDirectory(): File = File(MOD_ID)

    fun scriptsDirectory(): File = rootDirectory().resolve("scripts")

    fun compilerJar(): File = rootDirectory().resolve(SCRIPTING_JAR)

    fun scriptingLibrariesDirectory(): File = rootDirectory().resolve(SCRIPTING_LIBRARIES_DIRECTORY)
}
