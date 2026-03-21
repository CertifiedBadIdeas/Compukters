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

package ru.lazyhat.compukterkraft.network.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import org.apache.commons.lang3.StringUtils
import ru.lazyhat.compukterkraft.network.client.ChatHelpers.coloured

interface TableFormatter {
    /**
     * Get additional padding for the component.
     *
     * @param component The component to pad
     * @param width     The desired width for the component
     * @return The padding for this component, or `null` if none is needed.
     */
    fun getPadding(
        component: Component,
        width: Int,
    ): Component?

    /**
     * Get the minimum padding between each column.
     *
     * @return The minimum padding.
     */
    val columnPadding: Int

    fun getWidth(component: Component): Int

    fun writeLine(
        label: String?,
        component: Component,
    )

    fun display(table: TableBuilder) {
        if (table.columns <= 0) return

        val id = table.id
        val columns = table.columns
        val maxWidths = IntArray(columns)

        val headers: ArrayList<Component>? = table.headers
        if (headers != null) {
            for (i in 0..<columns) maxWidths[i] = getWidth(headers[i])
        }

        for (row in table.rows) {
            for (i in 0..<row.size) {
                val width = getWidth(row[i])
                if (width > maxWidths[i]) maxWidths[i] = width
            }
        }

        // Add a small amount of padding after each column
        run {
            val padding = this.columnPadding
            for (i in 0..<maxWidths.size - 1) maxWidths[i] += padding
        }

        // And compute the total width
        var totalWidth = (columns - 1) * getWidth(SEPARATOR)
        for (x in maxWidths) totalWidth += x

        if (headers != null) {
            val line: MutableComponent = Component.literal("")
            for (i in 0..<columns - 1) {
                line.append(headers[i])
                val padding = getPadding(headers[i], maxWidths[i])
                if (padding != null) line.append(padding)
                line.append(SEPARATOR)
            }
            line.append(headers[columns - 1])

            writeLine(id, line)

            // Write a separator line. We round the width up rather than down to make
            // it a tad prettier.
            val rowCharWidth = getWidth(HEADER)
            val rowWidth = totalWidth / rowCharWidth + (if (totalWidth % rowCharWidth == 0) 0 else 1)
            writeLine(
                id,
                coloured(
                    StringUtils.repeat(HEADER.string, rowWidth),
                    ChatFormatting.GRAY,
                ),
            )
        }

        for (row in table.rows) {
            val line: MutableComponent = Component.literal("")
            for (i in 0..<columns - 1) {
                line.append(row[i])
                val padding = getPadding(row[i], maxWidths[i])
                if (padding != null) line.append(padding)
                line.append(SEPARATOR)
            }
            line.append(row[columns - 1])
            writeLine(id, line)
        }

        if (table.additional > 0) {
            writeLine(
                id,
                Component.translatable("commands.compukterkraft.generic.additional_rows", table.additional).withStyle(ChatFormatting.AQUA),
            )
        }
    }

    companion object {
        val SEPARATOR: Component = coloured("| ", ChatFormatting.GRAY)
        val HEADER: Component = coloured("=", ChatFormatting.GRAY)
    }
}
