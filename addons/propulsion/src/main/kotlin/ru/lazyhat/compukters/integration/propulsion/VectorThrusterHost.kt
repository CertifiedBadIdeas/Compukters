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
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import propulsion.CreativeVectorThrusterState
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralLookupStatus
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

internal class VectorThrusterHost(
    private val computer: CompuktersComputerContext,
    private val leases: ThrusterControlLeases<CreativeVectorThrusterBlockEntity>,
) {
    private class BoundThruster(
        val entity: CreativeVectorThrusterBlockEntity,
        private val reachable: () -> Boolean,
    ) {
        private var stale = false

        fun valid(): Boolean {
            if (!stale && !reachable()) stale = true
            return !stale
        }
    }

    private val handles = mutableMapOf<Int, BoundThruster>()
    private var nextHandle = 1

    fun acquire(name: String): AddonCallResult<Int> {
        checkThread()
        val lookup = computer.findPeripheral(name)
        if (lookup.status != CompuktersPeripheralLookupStatus.FOUND) return unavailable()
        val device = requireNotNull(lookup.device)
        if (device.deviceKey != DEVICE_KEY) return unavailable()
        val entity = resolve(computer.level, device.anchor) ?: return unavailable()
        handles.entries.firstOrNull { it.value.entity === entity && it.value.valid() }?.let { return addonCompleted(it.key) }
        if (handles.size >= 256 || nextHandle == Int.MAX_VALUE) return invalid("Creative Vector Thruster handle limit exceeded")
        val handle = nextHandle++
        handles[handle] =
            BoundThruster(entity) {
                resolve(computer.level, device.anchor) === entity && computer.isPeripheralReachable(device)
            }
        return addonCompleted(handle)
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

    fun close(handle: Int): AddonCallResult<Unit> {
        checkThread()
        val bound = handles.remove(handle) ?: return unavailable()
        leases.release(bound.entity, this)
        return addonCompleted(Unit)
    }

    fun reset() {
        checkThread()
        leases.releaseOwner(this)
        handles.clear()
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
        val bound = handles[handle] ?: return unavailable()
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
            entity.clearPeripheralThrustOutput()
            entity.setDigitalInput(1f)
            entity.setDigitalInput(0f)
            entity.setControlMode(ControlMode.NORMAL)
            val level = entity.level
            if (level != null && level.hasChunkAt(entity.blockPos)) entity.setRedstoneInput(level.getBestNeighborSignal(entity.blockPos))
            entity.setChanged()
        }
    }
}
