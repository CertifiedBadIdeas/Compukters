package ru.lazyhat.compukterkraft.core.computer.runtime.ports

import java.util.UUID

/** Bridges per-player stdout byte streams from a runtime device to the network layer,
 *  and answers session-validity questions that depend on platform state (open menus). */
interface TerminalNetworkBridge {
    /** True if [playerUuid] currently has menu [containerId] open and that menu is
     *  still bound to the runtime device identified by [deviceId]. */
    fun isSessionStillBound(playerUuid: UUID, containerId: Int, deviceId: Int): Boolean

    /** Send raw stdout bytes to the player; no-op if the player is offline. */
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}
