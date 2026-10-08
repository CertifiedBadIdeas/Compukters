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

package ru.lazyhat.compukters.integration.propulsion

import ru.lazyhat.compukters.api.addon.ResourceCheckpointReader
import ru.lazyhat.compukters.api.addon.ResourceCheckpointWriter

/** Commands copied from upstream only at capture; no live command mirror. */
internal data class ThrusterCommand(
    val handle: Int,
    val peripheralMode: Boolean,
    val digitalInput: Float,
    val thrustPercent: Int = 0,
    val vectorX: Float? = null,
    val vectorY: Float? = null,
    val thrustOutput: Float? = null,
)

internal data class PropulsionCheckpoint(
    val ordinary: List<ThrusterCommand>,
    val vector: List<ThrusterCommand>,
) {
    fun encode(): ByteArray {
        val writer = ResourceCheckpointWriter()
        writer.int(1)

        fun commands(
            values: List<ThrusterCommand>,
            vector: Boolean,
        ) {
            validate(values, vector)
            writer.int(values.size)
            values.forEach { value ->
                writer.int(value.handle)
                writer.int(if (value.peripheralMode) 1 else 0)
                writer.int(value.digitalInput.toRawBits())
                writer.int(value.thrustPercent)
                writer.int(if (value.vectorX != null) 1 else 0)
                value.vectorX?.let {
                    writer.int(it.toRawBits())
                    writer.int(requireNotNull(value.vectorY).toRawBits())
                }
                writer.int(if (value.thrustOutput != null) 1 else 0)
                value.thrustOutput?.let { writer.int(it.toRawBits()) }
            }
        }
        require(ordinary.size + vector.size <= 1024)
        require((ordinary + vector).map { it.handle }.distinct().size == ordinary.size + vector.size)
        commands(ordinary, false)
        commands(vector, true)
        return writer.finish()
    }

    companion object {
        fun decode(bytes: ByteArray): PropulsionCheckpoint {
            val reader = ResourceCheckpointReader(bytes)
            require(reader.int() == 1) { "Unsupported Propulsion resource format" }

            fun flag(): Boolean = reader.int().also { require(it in 0..1) } == 1

            fun commands(vector: Boolean): List<ThrusterCommand> =
                List(reader.count(1024)) {
                    val handle = reader.int()
                    val peripheralMode = flag()
                    val digitalInput = Float.fromBits(reader.int())
                    val percent = reader.int()
                    val steering = if (flag()) Float.fromBits(reader.int()) to Float.fromBits(reader.int()) else null
                    val thrust = if (flag()) Float.fromBits(reader.int()) else null
                    ThrusterCommand(handle, peripheralMode, digitalInput, percent, steering?.first, steering?.second, thrust)
                }.also { validate(it, vector) }
            val result = PropulsionCheckpoint(commands(false), commands(true))
            reader.finish()
            require(result.ordinary.size + result.vector.size <= 1024)
            require((result.ordinary + result.vector).map { it.handle }.distinct().size == result.ordinary.size + result.vector.size)
            return result
        }

        private fun validate(
            values: List<ThrusterCommand>,
            vector: Boolean,
        ) {
            require(values.size <= 1024)
            require(values.map { it.handle }.distinct().size == values.size)
            values.forEach {
                require(it.handle > 0 && it.digitalInput.isFinite() && it.digitalInput in 0f..1f)
                require((it.vectorX == null) == (it.vectorY == null))
                require(it.vectorX == null || (it.vectorX.isFinite() && it.vectorX in -1f..1f))
                require(it.vectorY == null || (it.vectorY.isFinite() && it.vectorY in -1f..1f))
                require(it.thrustOutput == null || (it.thrustOutput.isFinite() && it.thrustOutput >= 0f))
                if (vector) {
                    require(it.thrustPercent == 0)
                } else {
                    require(it.thrustPercent in 1..100 && it.vectorX == null && it.thrustOutput == null)
                }
            }
        }
    }
}
