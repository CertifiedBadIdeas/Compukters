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

package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.core.computer.vm.VmContext
import ru.lazyhat.compukterkraft.lang.runtime.ComputerSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason

class VmSystemApi(
    private val ctx: VmContext,
    override val computerId: Int,
    private val currentTickProvider: () -> Long,
    private val labelProvider: () -> String?,
) : ComputerSystemApi {
    override val label: String? get() = labelProvider()
    override val currentTick: Long get() = currentTickProvider()

    override fun queueEvent(
        name: String,
        arguments: List<Any?>,
    ) {
        ctx.enqueueEvent(VmEvent(name, arguments))
    }

    override fun shutdown() {
        ctx.stop(VmStopReason.REQUESTED)
    }

    override fun reboot() {
        ctx.stop(VmStopReason.REBOOT)
    }

    override fun log(message: String) {
        ctx.log("VM[$computerId] $message")
    }
}
