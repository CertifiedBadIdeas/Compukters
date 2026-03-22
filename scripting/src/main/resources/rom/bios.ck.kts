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

package rom

import ru.lazyhat.compukterkraft.machine.ComputerProgram
import ru.lazyhat.compukterkraft.machine.ComputerRuntime

object BootProgram : ComputerProgram {
    override suspend fun run(runtime: ComputerRuntime) {
        runtime.terminal.clear()
        runtime.terminal.printLine("Compukter Kraft OS")
        runtime.terminal.printLine("Computer #${runtime.system.computerId} (${runtime.profile.displayName})")
        runtime.terminal.printLine("Waiting for events...")

        while (true) {
            val event = runtime.pullEvent()
            runtime.terminal.printLine("event: ${event.name}")
            runtime.yield()
        }
    }
}

BootProgram
