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
package ru.lazyhat.compukterkraft.common.network.text

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import ru.lazyhat.compukterkraft.common.computer.context.ServerComputer

/**
 * Various helpers for building chat messages.
 */
object ChatHelpers {
    private val HEADER: ChatFormatting = ChatFormatting.LIGHT_PURPLE

    fun coloured(
        text: String?,
        colour: ChatFormatting,
    ): MutableComponent = text(text).withStyle(colour)

    fun text(text: String?): MutableComponent = Component.literal(text ?: "")

    fun list(vararg children: Component): MutableComponent {
        val component: MutableComponent = Component.empty()
        for (child in children) {
            component.append(child)
        }
        return component
    }

    fun position(pos: BlockPos): MutableComponent = Component.literal(pos.toShortString())

    fun bool(value: Boolean): MutableComponent =
        if (value) {
            Component.translatable("commands.compukterkraft.generic.yes").withStyle(ChatFormatting.GREEN)
        } else {
            Component.translatable("commands.compukterkraft.generic.no").withStyle(ChatFormatting.RED)
        }

    fun link(
        component: MutableComponent,
        command: String,
        toolTip: Component,
    ): Component = link(component, ClickEvent(ClickEvent.Action.RUN_COMMAND, command), toolTip)

    fun clientLink(
        component: MutableComponent,
        command: String,
        toolTip: Component,
    ): Component {
        val event = ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)
        return link(component, event, toolTip)
    }

    fun link(
        component: Component,
        click: ClickEvent?,
        toolTip: Component,
    ): Component {
        var style = component.style

        if (style.getColor() == null) style = style.withColor(ChatFormatting.YELLOW)
        style = style.withClickEvent(click)
        style = style.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, toolTip))

        return component.copy().withStyle(style)
    }

    fun header(text: String?): MutableComponent = coloured(text, HEADER)

    fun copy(text: String): MutableComponent =
        Component.literal(text).withStyle(
            { s: Style? ->
                s!!
                    .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("gui.compukterkraft.tooltip.copy")))
            },
        )

    fun makeComputerCommand(
        command: String?,
        computer: ServerComputer,
    ): String = String.format("/compukterkraft %s @c[instance=%s]", command, computer.instanceID)

    fun makeComputerDumpCommand(computer: ServerComputer): Component =
        link(
            text("#" + computer.instanceID),
            makeComputerCommand("dump", computer),
            Component.translatable("commands.compukterkraft.dump.action"),
        )
}
