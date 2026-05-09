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

package ru.lazyhat.compukterkraft.core.device.runtime.ports

import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID

interface DisplayNetworkBridge {
    fun isDisplaySessionStillBound(
        playerUuid: UUID,
        containerId: Int,
        deviceId: Int,
        displayId: Int,
    ): Boolean

    fun sendDisplayFrame(
        playerUuid: UUID,
        containerId: Int,
        frame: DisplayFrameDelta,
    )

    fun sendNativeDisplayFrameBytes(
        playerUuid: UUID,
        containerId: Int,
        payload: ByteArray,
    ) {
        for (frame in NativeDisplayFrameCodec.decodeFrames(payload)) {
            sendDisplayFrame(playerUuid, containerId, frame)
        }
    }
}

object NoopDisplayNetworkBridge : DisplayNetworkBridge {
    override fun isDisplaySessionStillBound(
        playerUuid: UUID,
        containerId: Int,
        deviceId: Int,
        displayId: Int,
    ): Boolean = false

    override fun sendDisplayFrame(
        playerUuid: UUID,
        containerId: Int,
        frame: DisplayFrameDelta,
    ) = Unit
}
