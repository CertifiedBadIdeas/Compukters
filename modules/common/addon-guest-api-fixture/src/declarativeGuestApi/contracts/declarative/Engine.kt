/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package declarative

import ru.lazyhat.compukters.api.contracts.GuestCall
import ru.lazyhat.compukters.api.contracts.GuestService
import ru.lazyhat.compukters.api.contracts.GuestResource
import ru.lazyhat.compukters.api.contracts.GuestDevice
import kotlin.math.Vector3d
import kotlin.math.Rotation3d

value class State(val position: Vector3d, val orientation: Rotation3d) {
    fun direction(): Vector3d = position.normalized()
}

value class Velocity(val x: Double, val y: Double, val z: Double) {
    fun direction(): Vector3d = Vector3d(x, y, z).normalized()
}

@GuestResource
interface Snapshot { @GuestCall fun close(): Unit }

@GuestDevice("declarative:engine")
interface Engine {
    @GuestCall fun speed(): Float
    @GuestCall fun aim(direction: Vector3d): Unit
    @GuestCall fun target(state: State): Unit
    @GuestCall fun state(): State
    @GuestCall fun close(): Unit
    @GuestCall fun snapshot(): Snapshot
    fun velocity(): Velocity {
        val position = state().position
        return Velocity(position.x, position.y, position.z)
    }
}

@GuestService
interface Values {
    @GuestCall fun integer(): Int
    @GuestCall fun longValue(): Long
    @GuestCall fun decimal(): Double
    @GuestCall fun boolean(): Boolean
    @GuestCall fun character(): Char
    @GuestCall fun text(): String
    @GuestCall fun bytes(): ByteArray
    @GuestCall fun floating(index: Int): Double
    @GuestCall fun single(index: Int): Float
    @GuestCall fun oversized(): String
}
