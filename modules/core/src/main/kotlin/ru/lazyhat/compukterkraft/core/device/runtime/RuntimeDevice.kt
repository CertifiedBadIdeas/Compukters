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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import java.util.UUID

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

/** Input role: accept VM events. Re-uses [DeviceEvents.Receiver] so that
 *  device implementations can plug into the existing event-dispatch pipeline. */
interface RuntimeDeviceInput : DeviceEvents.Receiver

/** Display-session role: per-player pixel display endpoint attachments. */
interface RuntimeDeviceDisplaySessions {
    fun attachDisplaySession(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    )
}

/** Metadata role: family/label. Access checks belong to the carrier
 *  (e.g. the BlockEntity), not to the runtime device itself. */
interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
}

interface RuntimeDeviceSnapshotPersistence {
    fun snapshotRuntimeState(): ByteArray?
}

interface RuntimeDeviceFailureState {
    val runtimeFailureMessage: String?
}

/** Umbrella: every present-day runtime device implements every role. Future minimal
 *  carriers (e.g. Pocket without terminal sessions) may implement only a subset; the
 *  umbrella is then narrowed accordingly. */
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceDisplaySessions,
    RuntimeDeviceMetadata
