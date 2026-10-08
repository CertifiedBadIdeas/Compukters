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

import dev.propulsionteam.propulsionsimulated.PropulsionConfig
import dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity.ControlMode
import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.creative_vector_thruster.CreativeVectorThrusterBlockEntity
import dev.ryanhcode.sable.Sable
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import propulsion.CreativeVectorThrusterMount
import propulsion.CreativeVectorThrusterState
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralAccessException
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralContract
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPersistentPeripheralEndpoint
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

internal class VectorThrusterHost(
    private val computer: CompuktersComputerContext,
    private val leases: ThrusterControlLeases<CreativeVectorThrusterBlockEntity>,
) {
    internal class BoundThruster(
        val entity: CreativeVectorThrusterBlockEntity,
        private val reachable: () -> Boolean,
    ) : CompuktersPersistentPeripheralEndpoint {
        override val identity: Any get() = entity
        override val persistentIdentity: String get() = (entity as TransientThrusterControl).`compukters$persistentIdentity`()

        private var stale = false

        override fun valid(): Boolean {
            if (!stale && !reachable()) stale = true
            return !stale
        }
    }

    private val handles = mutableMapOf<Int, BoundThruster>()

    fun acquire(name: String): AddonCallResult<Int> {
        checkThread()
        return try {
            val handle = computer.peripheralNamed(contract, name)
            if (handle == 0) unavailable() else addonCompleted(handle)
        } catch (failure: CompuktersPeripheralAccessException) {
            addonFailed(failure.kind, failure.message)
        }
    }

    fun mount(handle: Int): AddonCallResult<CreativeVectorThrusterMount> =
        withHandle(handle) { bound ->
            val entity = bound.entity
            val computerBody = Sable.HELPER.getContaining(computer.level, computer.position)
            val engineBody = Sable.HELPER.getContaining(computer.level, entity.blockPos)
            if (computerBody !== engineBody) {
                return@withHandle addonFailed(HostFailureKind.UNAVAILABLE, "Thruster and computer are on different constructions")
            }
            val offset = entity.blockPos.subtract(computer.position)
            val direction = entity.facing
            addonCompleted(
                CreativeVectorThrusterMount(
                    computerBody?.uniqueId?.toString() ?: "",
                    offset.x,
                    offset.y,
                    offset.z,
                    direction.stepX,
                    direction.stepY,
                    direction.stepZ,
                ),
            )
        }

    fun state(handle: Int): AddonCallResult<CreativeVectorThrusterState> =
        withHandle(handle) { bound ->
            val entity = bound.entity
            addonCompleted(
                CreativeVectorThrusterState(
                    computer.level.gameTime,
                    entity.throttle.toDouble(),
                    entity.effectiveThrottle.toDouble(),
                    (entity as CreativeVectorThrustAccess).`compukters$baseThrustKn`(),
                    maxThrustKn(),
                    entity.hasPeripheralThrustOverride(),
                    entity.currentThrust.toDouble() / PropulsionConfig.getThrustUnitsPerKnOrDefault(),
                    entity.targetVectorX.toDouble(),
                    entity.targetVectorY.toDouble(),
                    entity.currentVectorX.toDouble(),
                    entity.currentVectorY.toDouble(),
                    entity.startupProgress.toDouble(),
                    entity.isActive,
                ),
            )
        }

    fun setThrottle(
        handle: Int,
        throttle: Double,
    ): AddonCallResult<Unit> {
        if (!throttle.isFinite() || throttle !in 0.0..1.0) return invalid("Throttle must be finite and between 0 and 1")
        return mutate(handle) { entity ->
            entity.setDigitalInput(throttle.toFloat())
            entity.setControlMode(ControlMode.PERIPHERAL)
        }
    }

    fun setVector(
        handle: Int,
        x: Double,
        y: Double,
    ): AddonCallResult<Unit> {
        if (!x.isFinite() || !y.isFinite() || x !in -1.0..1.0 || y !in -1.0..1.0) return invalid("Invalid vector coordinates")
        return mutate(handle) { (it as VectorThrusterControl).`compukters$setVector`(x.toFloat(), y.toFloat()) }
    }

    fun setThrustKn(
        handle: Int,
        thrust: Double,
    ): AddonCallResult<Unit> {
        if (!thrust.isFinite() || thrust !in 0.0..maxThrustKn()) return invalid("Thrust must be finite and within the configured kN limit")
        val output = thrust * PropulsionConfig.getThrustUnitsPerKnOrDefault()
        if (!output.isFinite() || output > Float.MAX_VALUE) return invalid("Thrust exceeds upstream Float precision")
        return mutate(handle) { it.setThrustOutput(output.toFloat()) }
    }

    fun clearThrustOverride(handle: Int): AddonCallResult<Unit> = mutate(handle) { it.clearPeripheralThrustOutput() }

    fun close(handle: Int): AddonCallResult<Unit> =
        withHandle(handle) { bound ->
            computer.closePeripheral(contract, handle)
            handles.remove(handle)
            leases.release(bound.entity, this)
            addonCompleted(Unit)
        }

    fun checkpoint(): List<ThrusterCommand> {
        checkThread()
        return handles.entries.sortedBy { it.key }.distinctBy { it.value.entity }.mapNotNull { (handle, bound) ->
            if (!leases.ownedBy(bound.entity, this)) return@mapNotNull null
            val entity = bound.entity
            val power = entity as TransientThrusterControl
            val vector = (entity as VectorThrusterControl).`compukters$hasVectorOverride`()
            ThrusterCommand(
                handle,
                power.`compukters$peripheralMode`(),
                power.`compukters$digitalInput`(),
                vectorX = if (vector) entity.targetVectorX else null,
                vectorY = if (vector) entity.targetVectorY else null,
                thrustOutput =
                    if (entity.hasPeripheralThrustOverride()) {
                        (entity as CreativeVectorThrustAccess)
                            .`compukters$peripheralThrustOutput`()
                    } else {
                        null
                    },
            )
        }
    }

    fun restoreCheckpoint(commands: List<ThrusterCommand>) {
        checkThread()
        commands.forEach { command ->
            val bound =
                try {
                    computer.peripheral(contract, command.handle)
                } catch (failure: CompuktersPeripheralAccessException) {
                    if (failure.kind != HostFailureKind.INPUT_OUTPUT) throw failure
                    return@forEach
                }
            val entity = bound.entity
            require(entity.computerBehaviour?.hasAttachedComputer() != true) { "Vector thruster controlled by ComputerCraft" }
            require(
                command.thrustOutput == null ||
                    command.thrustOutput <= (maxThrustKn() * PropulsionConfig.getThrustUnitsPerKnOrDefault()).toFloat(),
            ) {
                "Restored thrust exceeds configured limit"
            }
            require(
                leases.claim(entity, this, bound::valid) { restoreRedstone(entity) },
            ) { "Vector thruster controlled by another program" }
            handles[command.handle] = bound
            entity.setDigitalInput(command.digitalInput)
            entity.setControlMode(if (command.peripheralMode) ControlMode.PERIPHERAL else ControlMode.NORMAL)
            if (command.vectorX != null) {
                (entity as VectorThrusterControl).`compukters$setVector`(command.vectorX, requireNotNull(command.vectorY))
            } else {
                (entity as VectorThrusterControl).`compukters$clearVector`()
            }
            // Power and steering must be restored before this setter publishes physical thrust.
            if (command.thrustOutput != null) {
                entity.setThrustOutput(command.thrustOutput)
            } else {
                entity.clearPeripheralThrustOutput()
            }
            entity.setChanged()
        }
    }

    fun reset() {
        checkThread()
        try {
            leases.releaseOwner(this)
        } finally {
            handles.clear()
        }
    }

    private fun mutate(
        handle: Int,
        action: (CreativeVectorThrusterBlockEntity) -> Unit,
    ): AddonCallResult<Unit> =
        withHandle(handle) { bound ->
            val entity = bound.entity
            val computerCraftAttached = entity.computerBehaviour?.hasAttachedComputer() == true
            if (computerCraftAttached) return@withHandle invalid("Creative Vector Thruster is controlled by ComputerCraft")
            val claimed =
                leases.claim(entity, this, {
                    bound.valid() && entity.computerBehaviour?.hasAttachedComputer() != true
                }) { restoreRedstone(entity) }
            if (!claimed) return@withHandle invalid("Creative Vector Thruster is controlled by another program")
            action(entity)
            addonCompleted(Unit)
        }

    private fun <T> withHandle(
        handle: Int,
        action: (BoundThruster) -> AddonCallResult<T>,
    ): AddonCallResult<T> {
        checkThread()
        val bound =
            try {
                computer.peripheral(contract, handle)
            } catch (failure: CompuktersPeripheralAccessException) {
                handles.remove(handle)?.let { leases.release(it.entity, this) }
                return addonFailed(failure.kind, failure.message)
            }
        handles[handle] = bound
        if (!bound.valid()) {
            leases.release(bound.entity, this)
            handles.remove(handle)
            return unavailable()
        }
        return action(bound)
    }

    private fun checkThread() = check(computer.level.server.isSameThread) { "Thrusters require the server thread" }

    private fun unavailable() =
        addonFailed(HostFailureKind.UNAVAILABLE, "Creative Vector Thruster is missing, closed, disconnected, replaced or unloaded")

    private fun invalid(message: String) = addonFailed(HostFailureKind.OTHER, message)

    companion object {
        val contract =
            CompuktersPeripheralContract("propulsion:creative_vector_thruster", DEVICE_KEY) { location ->
                resolve(location.level, location.position)?.let { entity ->
                    BoundThruster(entity) {
                        resolve(location.level, location.position) === entity && location.isReachable()
                    }
                }
            }

        const val DEVICE_KEY = "creative_vector_thruster"

        fun resolve(
            level: ServerLevel,
            position: BlockPos,
        ): CreativeVectorThrusterBlockEntity? {
            if (!level.hasChunkAt(position)) return null
            val entity = level.getBlockEntity(position) as? CreativeVectorThrusterBlockEntity ?: return null
            return entity.takeUnless { it.isRemoved }
        }

        private fun maxThrustKn(): Double = PropulsionConfig.CREATIVE_VECTOR_THRUSTER_MAX_THRUST.get()

        private fun restoreRedstone(entity: CreativeVectorThrusterBlockEntity) {
            (entity as VectorThrusterControl).`compukters$clearVector`()
            if (entity.computerBehaviour?.hasAttachedComputer() == true) return
            (entity as TransientThrusterControl).`compukters$clearProgramPower`()
            entity.setControlMode(ControlMode.NORMAL)
            val level = entity.level
            if (level != null && level.hasChunkAt(entity.blockPos)) entity.setRedstoneInput(level.getBestNeighborSignal(entity.blockPos))
            // This setter immediately publishes physical thrust; restore power first.
            entity.clearPeripheralThrustOutput()
            entity.setChanged()
        }
    }
}
