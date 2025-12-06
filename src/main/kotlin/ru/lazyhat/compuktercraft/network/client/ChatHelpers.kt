// SPDX-FileCopyrightText: 2017 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import ru.lazyhat.compuktercraft.computer.ServerComputer

/**
 * Various helpers for building chat messages.
 */
object ChatHelpers {
    private val HEADER: ChatFormatting = ChatFormatting.LIGHT_PURPLE

    fun coloured(
        text: String?,
        colour: ChatFormatting,
    ): MutableComponent = text(text).withStyle(colour)

    fun text(text: String?): MutableComponent = Component.literal(if (text == null) "" else text)

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
            Component.translatable("commands.compuktercraft.generic.yes").withStyle(ChatFormatting.GREEN)
        } else {
            Component.translatable("commands.compuktercraft.generic.no").withStyle(ChatFormatting.RED)
        }

    fun link(
        component: MutableComponent,
        command: String,
        toolTip: Component,
    ): Component = ChatHelpers.link(component, ClickEvent(ClickEvent.Action.RUN_COMMAND, command), toolTip)

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
        var style = component.getStyle()

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
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("gui.compuktercraft.tooltip.copy")))
            },
        )

    fun makeComputerCommand(
        command: String?,
        computer: ServerComputer,
    ): String = String.format("/compuktercraft %s @c[instance=%s]", command, computer.instanceUUID)

    fun makeComputerDumpCommand(computer: ServerComputer): Component =
        link(
            text("#" + computer.instanceID),
            makeComputerCommand("dump", computer),
            Component.translatable("commands.compuktercraft.dump.action"),
        )
}
