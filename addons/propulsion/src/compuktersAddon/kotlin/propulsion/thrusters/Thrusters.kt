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

package propulsion.thrusters

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** Immutable state observed on the server; thrust is expressed in kilonewtons. */
public data class CreativeThrusterState(
    public val gameTick: Long,
    public val throttle: Double,
    public val effectiveThrottle: Double,
    public val thrustPercent: Int,
    public val configuredThrustKn: Double,
    public val currentThrustKn: Double,
    public val startupProgress: Double,
    public val active: Boolean,
    public val startingUp: Boolean,
    public val fadingOut: Boolean,
    public val width: Int,
    public val unobstructedBlocks: Int,
)

public class CreativeThruster internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<CreativeThruster>("propulsion:creative_thruster") {
        override fun wrap(handle: Int): CreativeThruster = CreativeThruster(handle)
    }

    override fun equals(other: Any?): Boolean = other is CreativeThruster && handle == other.handle

    override fun hashCode(): Int = handle

    override fun toString(): String = "CreativeThruster(handle=$handle)"

    public fun state(): CreativeThrusterState = ThrusterBindings.state(handle)

    /** Claims exclusive program control. Propulsion applies its ordinary startup and obstruction rules. */
    public fun setThrottle(throttle: Double) {
        if (!(throttle >= 0.0 && throttle <= 1.0)) throw IllegalArgumentException("Throttle must be finite and between 0 and 1")
        ThrusterBindings.setThrottle(handle, throttle)
    }

    /** Changes the saved engine setting, independently of the current throttle. */
    public fun setThrustPercent(percent: Int) {
        if (percent < 1 || percent > 100) throw IllegalArgumentException("Thrust percentage must be between 1 and 100")
        ThrusterBindings.setThrustPercent(handle, percent)
    }

    /** Invalidates this handle and releases digital control back to redstone. */
    public fun close() { ThrusterBindings.close(handle) }
}

public object Thrusters {
    /** Resolves a named Creative Vector Thruster without claiming control. */
    public fun creativeVector(name: String): CreativeVectorThruster = CreativeVectorThruster(VectorThrusterBindings.vectorAcquire(name))

    /** Resolves a named Creative Thruster without claiming control. */
    public fun creative(name: String): CreativeThruster = CreativeThruster(ThrusterBindings.acquire(name))
}

private object ThrusterBindings {
    external fun acquire(name: String): Int
    external fun state(handle: Int): CreativeThrusterState
    external fun setThrottle(handle: Int, throttle: Double)
    external fun setThrustPercent(handle: Int, percent: Int)
    external fun close(handle: Int)
}
