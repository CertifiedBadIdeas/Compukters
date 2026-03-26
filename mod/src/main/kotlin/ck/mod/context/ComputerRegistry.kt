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

package ck.mod.context

import ck.mod.computer.ServerComputer
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ComputerRegistry {
    companion object {
        @OptIn(ExperimentalTime::class)
        val RANDOM = Random(Clock.System.now().toEpochMilliseconds())
    }

    val sessionId = RANDOM.nextInt()
    private val computersByInstanceId: Int2ObjectMap<ServerComputer> = Int2ObjectOpenHashMap()

    fun getServerComputer(instanceId: Int): ServerComputer? = computersByInstanceId[instanceId]

    fun addServerComputer(serverComputer: ServerComputer) {
        check(!computersByInstanceId.containsKey(serverComputer.instanceID)) {
            "Computer with ${serverComputer.instanceID} already exists!"
        }

        computersByInstanceId.put(serverComputer.instanceID, serverComputer)
    }

    fun removeServerComputer(instanceId: Int): ServerComputer? = computersByInstanceId.remove(instanceId)
}
