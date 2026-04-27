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

package ru.lazyhat.compukterkraft.impl.create

import ru.lazyhat.compukterkraft.common.peripheral.BlockPeripheralRegistry
import ru.lazyhat.compukterkraft.core.LOGGER

/**
 * Single entry point for activating Create-specific behavior. Called only after
 * [CreateCompatBootstrap] has confirmed Create is loaded, so anything that touches
 * `com.simibubi.create.*` is safe to reference directly inside this module from here on.
 *
 * Right now we only register a no-op [CreateBlockPeripheralProvider] to prove the SPI wiring;
 * concrete Create block detection lands in a follow-up that may freely import Create classes.
 */
object CreateCompatRegistrar {
    fun register() {
        BlockPeripheralRegistry.register(CreateBlockPeripheralProvider)
        LOGGER.info { "Create compatibility enabled" }
    }
}
