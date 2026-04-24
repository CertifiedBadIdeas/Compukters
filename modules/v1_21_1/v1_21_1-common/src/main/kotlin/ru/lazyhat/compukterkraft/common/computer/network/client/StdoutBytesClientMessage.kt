/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ru.lazyhat.compukterkraft.common.computer.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages

/**
 * Server → client: a chunk of stdout bytes produced by a computer's VM.
 *
 * Carries the target [containerId] — the client routes bytes to the currently
 * open [ComputerMenu][ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu]
 * whose container matches. The menu feeds them into its client-side
 * [ClientTerminalBuffer][ru.lazyhat.compukterkraft.common.computer.client.ClientTerminalBuffer].
 */
class StdoutBytesClientMessage : NetworkMessage<ClientNetworkContext> {
    private val containerId: Int
    private val bytes: ByteArray

    constructor(containerId: Int, bytes: ByteArray) {
        this.containerId = containerId
        this.bytes = bytes
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        bytes = buf.readByteArray()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeByteArray(bytes)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleStdoutBytes(containerId, bytes)
    }

    override fun type(): MessageType<StdoutBytesClientMessage> = NetworkMessages.STDOUT_BYTES
}
