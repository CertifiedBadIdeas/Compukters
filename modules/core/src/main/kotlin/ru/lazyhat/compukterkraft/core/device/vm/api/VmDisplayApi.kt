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

    override fun copyRect(
        displayId: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    ) {
        registry.copyRect(displayId, srcX, srcY, width, height, dstX, dstY)
    }

    override fun blitMono(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        registry.blitMono(displayId, x, y, width, height, mask, foreground, background)
    }

    override fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        registry.blitMono5x7(displayId, x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
    }

    override fun blitMono5x7Text(
        displayId: Int,
        x: Int,
        y: Int,
        text: String,
        foreground: Int,
        background: Int,
    ) {
        registry.blitMono5x7Text(displayId, x, y, text, foreground, background)
    }

    override fun present(displayId: Int) = registry.present(displayId)
}
