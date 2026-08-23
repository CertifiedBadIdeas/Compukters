/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.impl

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.impl.terminal.TerminalNetwork
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime

@Mod(MOD_ID)
class CompuktersMod(
    eventBus: IEventBus,
) {
    init {
        val native = requireNativeRuntime()
        CompuktersRegistry.register(eventBus)
        eventBus.addListener(TerminalNetwork::register)
        LOGGER.debug { "$MOD_ID loaded native VM from ${native.source}" }
    }

    companion object {
        internal fun requireNativeRuntime() = VmRuntime.requireLoaded()
    }
}
