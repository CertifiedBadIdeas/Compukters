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

import terminal;
import system;
import events;

fun main() {
    terminal.clear();
    terminal.printLine("Compukter Kraft OS");
    terminal.printLine("Computer #" + system.computerId());
    terminal.printLine("Waiting for events...");

    while (true) {
        let event: Event = events.pull();
        terminal.printLine("event: " + event.name);
        yield();
    }
}
