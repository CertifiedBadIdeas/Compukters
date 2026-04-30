package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/** Lifecycle role: turn on/off, tick, query state. */
interface RuntimeDeviceLifecycle {
    val deviceId: Int
    val isOn: Boolean
    fun turnOn()
    fun shutdown()
    fun reboot()
    fun serverTick()
    fun close()
}

/** Input role: accept VM events. */
interface RuntimeDeviceInput {
    fun queueEvent(event: String, arguments: Array<Any>)
}

/** Screen role: read latest screen snapshot (used by workbench / legacy clients). */
interface RuntimeDeviceScreen {
    val lastScreenSnapshot: ScreenBufferSnapshot?
}

/** Terminal-session role: per-player byte-stream attachments. */
interface RuntimeDeviceTerminalSessions {
    fun attachTerminalSession(playerUuid: UUID, containerId: Int, cols: Int, rows: Int)
    fun resizeTerminalSession(playerUuid: UUID, cols: Int, rows: Int)
    fun detachTerminalSession(playerUuid: UUID)
}

/** Metadata role: family/label, access checks. */
interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
    fun checkUsable(player: PlayerHandle): Boolean
}

/** Umbrella: every present-day runtime device implements every role. Future minimal
 *  carriers (e.g. Pocket without terminal sessions) may implement only a subset; the
 *  umbrella is then narrowed accordingly. */
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceTerminalSessions,
    RuntimeDeviceMetadata
