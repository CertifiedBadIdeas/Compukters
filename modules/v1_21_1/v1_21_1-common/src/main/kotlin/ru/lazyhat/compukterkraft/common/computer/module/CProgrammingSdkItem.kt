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

package ru.lazyhat.compukterkraft.common.computer.module

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

const val C_PROGRAMMING_SDK_ARTIFACT_IDENTITY: String = "c_sdk_v1"

internal fun cProgrammingSdkStack(stack: ItemStack): ItemStack =
    stack.apply {
        sdkArtifactIdentity = C_PROGRAMMING_SDK_ARTIFACT_IDENTITY
    }

class CProgrammingSdkItem(
    properties: Properties,
) : Item(properties) {
    override fun getDefaultInstance(): ItemStack = cProgrammingSdkStack(super.getDefaultInstance())

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        list.add(
            Component
                .translatable("item.compukterkraft.c_programming_sdk.tooltip")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
    }
}
