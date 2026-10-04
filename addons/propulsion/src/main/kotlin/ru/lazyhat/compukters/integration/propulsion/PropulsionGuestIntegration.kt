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
import dev.propulsionteam.propulsionsimulated.content.thruster.thruster.creative_thruster.CreativeThrusterBlockEntity
import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.creative_vector_thruster.CreativeVectorThrusterBlockEntity
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import propulsion.CreativeThrusterState
import propulsion.CreativeVectorThrusterMount
import propulsion.CreativeVectorThrusterState
import propulsion.PropulsionAddonContract
import propulsion.PropulsionCapabilityHandler
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralDevice
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralLookupStatus
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralProvider
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

internal object PropulsionGuestIntegration {
    private const val DEVICE_KEY = "creative_thruster"
    private val leases = ThrusterControlLeases<CreativeThrusterBlockEntity>()
    private val vectorLeases = ThrusterControlLeases<CreativeVectorThrusterBlockEntity>()

    @JvmStatic
    fun isControlled(entity: Any): Boolean =
        when (entity) {
            is CreativeThrusterBlockEntity -> leases.contains(entity)
            is CreativeVectorThrusterBlockEntity -> vectorLeases.contains(entity)
            else -> false
        }

    fun register() {
        CompuktersAddonRegistry.register(
            PropulsionAddonContract.guestApi(PropulsionGuestIntegration::class.java),
            CompuktersAddonHostFactory { computer -> PropulsionAddonContract.host(ThrusterHost(computer, leases)) },
            CompuktersPeripheralProvider { contact ->
                controller(contact.level, contact.position)?.let { CompuktersPeripheralDevice(it.blockPos, DEVICE_KEY) }
                    ?: VectorThrusterHost.resolve(contact.level, contact.position)?.let {
                        CompuktersPeripheralDevice(it.blockPos, VectorThrusterHost.DEVICE_KEY)
                    }
            },
        )
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> {
            leases.reap()
            vectorLeases.reap()
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            leases.releaseAll()
            vectorLeases.releaseAll()
        }
    }

    fun controller(
        level: ServerLevel,
        position: net.minecraft.core.BlockPos,
    ): CreativeThrusterBlockEntity? {
        if (!level.hasChunkAt(position)) return null
        val member = level.getBlockEntity(position) as? CreativeThrusterBlockEntity ?: return null
        val controller = member.controllerBE ?: return null
        if (!level.hasChunkAt(controller.blockPos) || controller.isRemoved ||
            level.getBlockEntity(controller.blockPos) !== controller
        ) {
            return null
        }
        return controller
    }

    internal class ThrusterHost(
        private val computer: CompuktersComputerContext,
        private val leases: ThrusterControlLeases<CreativeThrusterBlockEntity>,
    ) : PropulsionCapabilityHandler {
        private class BoundThruster(
            val entity: CreativeThrusterBlockEntity,
            private val reachable: () -> Boolean,
        ) {
            private var stale = false

            fun valid(): Boolean {
                if (!stale && !reachable()) stale = true
                return !stale
            }
        }

        private val vectorHost = VectorThrusterHost(computer, vectorLeases)

        override fun vectorAcquire(name: String): AddonCallResult<Int> = vectorHost.acquire(name)

        override fun vectorMount(handle: Int): AddonCallResult<CreativeVectorThrusterMount> = vectorHost.mount(handle)

        override fun vectorState(handle: Int): AddonCallResult<CreativeVectorThrusterState> = vectorHost.state(handle)

        override fun vectorSetThrottle(
            handle: Int,
            throttle: Double,
        ): AddonCallResult<Unit> = vectorHost.setThrottle(handle, throttle)

        override fun vectorSetVector(
            handle: Int,
            x: Double,
            y: Double,
        ): AddonCallResult<Unit> = vectorHost.setVector(handle, x, y)

        override fun vectorSetThrustKn(
            handle: Int,
            thrust: Double,
        ): AddonCallResult<Unit> = vectorHost.setThrustKn(handle, thrust)

        override fun vectorClearThrustOverride(handle: Int): AddonCallResult<Unit> = vectorHost.clearThrustOverride(handle)

        override fun vectorClose(handle: Int): AddonCallResult<Unit> = vectorHost.close(handle)

        private val handles = mutableMapOf<Int, BoundThruster>()
        private var nextHandle = 1

        override fun acquire(name: String): AddonCallResult<Int> {
            checkThread()
            val lookup = computer.findPeripheral(name)
            if (lookup.status != CompuktersPeripheralLookupStatus.FOUND) {
                return addonFailed(HostFailureKind.UNAVAILABLE, "Creative Thruster name is missing, ambiguous or unavailable")
            }
            val device = requireNotNull(lookup.device)
            if (device.deviceKey != DEVICE_KEY) return unavailable()
            val entity = controller(computer.level, device.anchor) ?: return unavailable()
            handles.entries.firstOrNull { it.value.entity === entity && it.value.valid() }?.let { return addonCompleted(it.key) }
            if (handles.size >= 256 || nextHandle == Int.MAX_VALUE) {
                return addonFailed(HostFailureKind.OTHER, "Creative Thruster handle limit exceeded")
            }
            val handle = nextHandle++
            handles[handle] =
                BoundThruster(entity) {
                    controller(computer.level, device.anchor) === entity && computer.isPeripheralReachable(device)
                }
            return addonCompleted(handle)
        }

        override fun state(handle: Int): AddonCallResult<CreativeThrusterState> =
            withHandle(handle) { bound ->
                val entity = bound.entity
                addonCompleted(
                    CreativeThrusterState(
                        computer.level.gameTime,
                        entity.throttle.toDouble(),
                        entity.effectiveThrottle.toDouble(),
                        entity.thrustConfig + 1,
                        entity.targetThrustNewtons.toDouble(), // Pinned 1.1.5 returns configured kN despite this method's name.
                        entity.currentThrust.toDouble() / PropulsionConfig.getThrustUnitsPerKnOrDefault(),
                        entity.startupProgress.toDouble(),
                        entity.isActive,
                        entity.isStartingUp,
                        entity.isFadingOut,
                        entity.width,
                        entity.unobstructedBlocks,
                    ),
                )
            }

        override fun setThrottle(
            handle: Int,
            throttle: Double,
        ): AddonCallResult<Unit> {
            if (!throttle.isFinite() || throttle !in 0.0..1.0) return invalidInput()
            return mutate(handle) { entity ->
                entity.setDigitalInput(throttle.toFloat())
                entity.setControlMode(ControlMode.PERIPHERAL)
            }
        }

        override fun setThrustPercent(
            handle: Int,
            percent: Int,
        ): AddonCallResult<Unit> {
            if (percent !in 1..100) return invalidInput()
            return mutate(handle) { it.setThrustConfig(percent - 1) }
        }

        override fun close(handle: Int): AddonCallResult<Unit> {
            checkThread()
            val bound = handles.remove(handle) ?: return unavailable()
            leases.release(bound.entity, this)
            return addonCompleted(Unit)
        }

        override fun reset() {
            checkThread()
            leases.releaseOwner(this)
            vectorHost.reset()
            handles.clear()
        }

        private fun mutate(
            handle: Int,
            action: (CreativeThrusterBlockEntity) -> Unit,
        ): AddonCallResult<Unit> =
            withHandle(handle) { bound ->
                val entity = bound.entity
                if (entity.computerBehaviour?.hasAttachedComputer() == true) {
                    return@withHandle addonFailed(HostFailureKind.OTHER, "Creative Thruster is controlled by ComputerCraft")
                }
                val claimed = leases.claim(entity, this, bound::valid) { restoreRedstone(entity) }
                if (!claimed) return@withHandle addonFailed(HostFailureKind.OTHER, "Creative Thruster is controlled by another program")
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
            addonFailed(HostFailureKind.UNAVAILABLE, "Creative Thruster handle is closed, disconnected, replaced or unloaded")

        private fun invalidInput() = addonFailed(HostFailureKind.OTHER, "Invalid Creative Thruster command value")

        private fun restoreRedstone(entity: CreativeThrusterBlockEntity) {
            // An attached CC peripheral owns its own detach policy; do not overwrite its commands.
            if (entity.computerBehaviour?.hasAttachedComputer() == true) return
            (entity as TransientThrusterControl).`compukters$clearProgramPower`()
            entity.setControlMode(ControlMode.NORMAL)
            val level = entity.level
            if (level != null && level.hasChunkAt(entity.blockPos)) entity.setRedstoneInput(level.getBestNeighborSignal(entity.blockPos))
            entity.updateThrust(entity.blockState)
            entity.setChanged()
        }
    }
}
