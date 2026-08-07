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

package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.device.runtime.ports.ServerThreadDispatcher
import java.util.concurrent.ConcurrentLinkedQueue

internal val directServerThreadDispatcher = ServerThreadDispatcher { task -> task() }

internal class RecordingServerThreadDispatcher : ServerThreadDispatcher {
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()

    val pendingTaskCount: Int
        get() = tasks.size

    override fun dispatch(task: () -> Unit) {
        tasks += task
    }

    fun runAll() {
        while (true) {
            tasks.poll()?.invoke() ?: return
        }
    }
}
