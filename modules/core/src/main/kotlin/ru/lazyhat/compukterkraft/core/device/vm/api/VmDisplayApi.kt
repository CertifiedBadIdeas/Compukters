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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDisplayApi

class VmDisplayApi(
    private val registry: DisplayRegistry,
) : DeviceDisplayApi {
    override fun primary(): Int = registry.firstDisplayId()

    override fun isAttached(displayId: Int): Boolean = registry.info(displayId) != null

    override fun width(displayId: Int): Int = registry.info(displayId)?.width ?: 0

    override fun height(displayId: Int): Int = registry.info(displayId)?.height ?: 0

    override fun clear(
        displayId: Int,
        rgb565: Int,
    ) {
        registry.clear(displayId, rgb565)
    }

    override fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        registry.setPixel(displayId, x, y, rgb565)
    }

    override fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        registry.fillRect(displayId, x, y, width, height, rgb565)
    }

    override fun present(displayId: Int) = registry.present(displayId)
}
