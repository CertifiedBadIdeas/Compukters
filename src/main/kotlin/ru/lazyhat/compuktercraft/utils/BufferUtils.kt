package ru.lazyhat.compuktercraft.utils

import net.minecraft.network.FriendlyByteBuf

@OptIn(ExperimentalUnsignedTypes::class)
fun FriendlyByteBuf.writeUByteArray(arr: UByteArray): FriendlyByteBuf = writeByteArray(arr.toByteArray())

@OptIn(ExperimentalUnsignedTypes::class)
fun FriendlyByteBuf.readUByteArray(): UByteArray = readByteArray().toUByteArray()
