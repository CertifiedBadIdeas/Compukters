/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("UNUSED_PARAMETER")

package compukter.compiler

object Compiler {
    suspend fun compile(
        source: String,
        output: String,
    ): Int = 0

    fun diagnostics(): String = ""
}
