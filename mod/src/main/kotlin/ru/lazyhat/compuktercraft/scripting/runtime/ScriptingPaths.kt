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

package ru.lazyhat.compuktercraft.scripting.runtime

import java.io.File

object ScriptingPaths {
    const val SCRIPTING_JAR = "CompukterCraftScripting.jar"

    fun rootDirectory(): File = File("compuktercraft")

    fun scriptsDirectory(): File = rootDirectory().resolve("scripts")

    fun scriptingJar(): File = rootDirectory().resolve(SCRIPTING_JAR)
}
