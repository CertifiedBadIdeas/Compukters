// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.text

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.apache.commons.lang3.StringUtils

class ServerTableFormatter(
    private val source: CommandSourceStack,
) : TableFormatter {
    public override fun getPadding(
        component: Component,
        width: Int,
    ): Component? {
        val extraWidth = width - getWidth(component)
        if (extraWidth <= 0) return null
        return Component.literal(StringUtils.repeat(' ', extraWidth))
    }

    override val columnPadding: Int = 1

    public override fun getWidth(component: Component): Int = component.string.length

    public override fun writeLine(
        label: String?,
        component: Component,
    ) = source.sendSuccess({ component }, false)
}
