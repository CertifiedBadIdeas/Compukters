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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.EventPayloadStore
import ru.lazyhat.compukterkraft.lang.runtime.DeviceEventApi

internal class VmEventApi(
    private val payloadStore: EventPayloadStore,
) : DeviceEventApi {
    override fun capture(arguments: List<Any?>): Pair<Int, Int> = payloadStore.capture(arguments)

    override fun argCount(eventId: Int): Int = payloadStore.argCount(eventId)

    override fun argInt(
        eventId: Int,
        index: Int,
    ): Int = payloadStore.argInt(eventId, index)

    override fun argBool(
        eventId: Int,
        index: Int,
    ): Boolean = payloadStore.argBool(eventId, index)

    override fun argString(
        eventId: Int,
        index: Int,
    ): String = payloadStore.argString(eventId, index)
}