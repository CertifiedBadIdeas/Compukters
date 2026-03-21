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

package ru.lazyhat.compukterkraft.network.client

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.apache.commons.lang3.StringUtils
import ru.lazyhat.compukterkraft.network.text.TableFormatter

class ClientTableFormatter(
    private val minecraft: Minecraft,
) : TableFormatter {
    override fun getPadding(
        component: Component,
        width: Int,
    ): Component? {
        val extraWidth = width - getWidth(component)
        if (extraWidth <= 0) return null
        return Component.literal(StringUtils.repeat(' ', extraWidth))
    }

    override val columnPadding: Int = 1

    override fun getWidth(component: Component): Int = minecraft.font.width(component)

    override fun writeLine(
        label: String?,
        component: Component,
    ) {
        minecraft.gui.chat.addMessage(component)
    }
}
