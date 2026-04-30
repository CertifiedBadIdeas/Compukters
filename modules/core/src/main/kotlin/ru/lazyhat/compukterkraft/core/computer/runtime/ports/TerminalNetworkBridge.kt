package ru.lazyhat.compukterkraft.core.computer.runtime.ports

import java.util.UUID

/** Bridges per-player stdout byte streams from a runtime device to the network layer. */
interface TerminalNetworkBridge {
    /** True if the player is currently connected to the server. */
    fun isPlayerOnline(playerUuid: UUID): Boolean

    /** Send raw stdout bytes to the player; no-op if the player is offline. */
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}
