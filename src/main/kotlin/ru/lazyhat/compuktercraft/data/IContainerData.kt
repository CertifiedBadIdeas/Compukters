package ru.lazyhat.compuktercraft.data

import net.minecraft.network.FriendlyByteBuf

interface IContainerData {
    fun toBytes(buffer: FriendlyByteBuf)
}
