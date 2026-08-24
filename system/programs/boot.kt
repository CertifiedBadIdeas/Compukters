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

import compukter.process.Process
import compukter.terminal.Terminal

suspend fun main() {
    val result = Process.run("/rom/shell", 15)
    if (result != 0) Terminal.write("boot failed: " + processFailure(result) + "\n")
}

private fun processFailure(result: Int): String {
    if (result == 1) return "invalid child capabilities"
    if (result == 8) return "invalid executable"
    if (result == 9) return "incompatible program"
    if (result == 10) return "failed to start"
    return "process status"
}
