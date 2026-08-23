/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.lazyhat.compukters.core.MOD_ID

data class TerminalSnapshotPayload(
    val position: BlockPos,
    val text: String,
    val status: String,
    val waitingForInput: Boolean,
    val openScreen: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalSnapshotPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TerminalSnapshotPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "terminal_snapshot"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalSnapshotPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload ->
                    buffer.writeBlockPos(payload.position)
                    buffer.writeUtf(payload.text, MAXIMUM_TRANSCRIPT_CODE_UNITS)
                    buffer.writeUtf(payload.status, MAXIMUM_STATUS_CODE_UNITS)
                    buffer.writeBoolean(payload.waitingForInput)
                    buffer.writeBoolean(payload.openScreen)
                },
                StreamDecoder { buffer ->
                    TerminalSnapshotPayload(
                        buffer.readBlockPos(),
                        buffer.readUtf(MAXIMUM_TRANSCRIPT_CODE_UNITS),
                        buffer.readUtf(MAXIMUM_STATUS_CODE_UNITS),
                        buffer.readBoolean(),
                        buffer.readBoolean(),
                    )
                },
            )

        private const val MAXIMUM_TRANSCRIPT_CODE_UNITS = 32 * 1024
        private const val MAXIMUM_STATUS_CODE_UNITS = 512
    }
}

data class TerminalRefreshPayload(
    val position: BlockPos,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalRefreshPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TerminalRefreshPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "terminal_refresh"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalRefreshPayload> =
            BlockPos.STREAM_CODEC.map(::TerminalRefreshPayload, TerminalRefreshPayload::position).cast()
    }
}

data class TerminalInputPayload(
    val position: BlockPos,
    val line: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalInputPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TerminalInputPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "terminal_input"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalInputPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload ->
                    buffer.writeBlockPos(payload.position)
                    buffer.writeUtf(payload.line, MAXIMUM_INPUT_CODE_UNITS)
                },
                StreamDecoder { buffer ->
                    TerminalInputPayload(buffer.readBlockPos(), buffer.readUtf(MAXIMUM_INPUT_CODE_UNITS))
                },
            )

        const val MAXIMUM_INPUT_CODE_UNITS = 4_096
    }
}
