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

package compukter.terminal

object Terminal {
    fun write(payload: String): Unit = Unit

    fun erasePrevious(): Unit = Unit

    fun clear(): Unit = Unit

    suspend fun awaitEvent(): Int = 0

    fun eventText(): String = ""

    fun eventKey(): Int = 0

    fun eventAction(): Int = 0

    fun eventModifiers(): Int = 0

    fun finishEvent(): Unit = Unit
}
