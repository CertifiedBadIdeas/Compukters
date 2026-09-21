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

package ru.lazyhat.compukters.integration.create

import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity
import create.CreateAddonContract
import create.CreateCapabilityHandler
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.addonPending
import ru.lazyhat.compukters.api.addon.addonPollCompleted
import ru.lazyhat.compukters.api.addon.addonPollFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralContact
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralDevice
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralLookupStatus
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralProvider
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

object CreateKineticsIntegration {
    fun register() {
        CompuktersAddonRegistry.register(
            CreateAddonContract.guestApi(CreateKineticsIntegration::class.java),
            CompuktersAddonHostFactory { computer ->
                CreateAddonContract.host(
                    KineticsHostState(
                        resolveSide = { side, kind -> resolveEndpoint(computer, side, kind) },
                        resolveName = { name, kind -> resolveNamedEndpoint(computer, name, kind) },
                    ),
                )
            },
            CompuktersPeripheralProvider(::resolvePeripheral),
        )
    }

    private fun resolvePeripheral(contact: CompuktersPeripheralContact): CompuktersPeripheralDevice? =
        when (contact.level.getBlockEntity(contact.position)) {
            is SpeedGaugeBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.SPEEDOMETER.deviceKey)
            }

            is StressGaugeBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.STRESSOMETER.deviceKey)
            }

            is SpeedControllerBlockEntity -> {
                CompuktersPeripheralDevice(contact.position, PeripheralKind.ROTATION_CONTROLLER.deviceKey)
            }

            else -> {
                null
            }
        }

    private fun resolveEndpoint(
        computer: CompuktersComputerContext,
        side: Int,
        kind: PeripheralKind,
    ): KineticsEndpoint? {
        val level = computer.level
        check(level.server.isSameThread) { "Create kinetics must be accessed on the server thread" }
        val direction = computer.adjacentDirection(side) ?: return null
        val position = computer.position.relative(direction)
        return resolveEndpoint(computer.level, position, kind)
    }

    private fun resolveNamedEndpoint(
        computer: CompuktersComputerContext,
        name: String,
        kind: PeripheralKind,
    ): KineticsNamedResolution {
        val lookup = computer.findPeripheral(name)
        return when (lookup.status) {
            CompuktersPeripheralLookupStatus.FOUND -> {
                val device = checkNotNull(lookup.device)
                if (device.deviceKey != kind.deviceKey) {
                    KineticsNamedResolution.Failed(
                        HostFailureKind.UNAVAILABLE,
                        "Named peripheral is not the requested Create kinetic device type",
                    )
                } else {
                    resolveEndpoint(computer.level, device.anchor, kind)?.let(KineticsNamedResolution::Found)
                        ?: KineticsNamedResolution.Failed(
                            HostFailureKind.UNAVAILABLE,
                            "Named Create kinetic device was removed, replaced, or unloaded",
                        )
                }
            }

            CompuktersPeripheralLookupStatus.MISSING -> {
                KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "No reachable Create peripheral has that name")
            }

            CompuktersPeripheralLookupStatus.AMBIGUOUS -> {
                KineticsNamedResolution.Failed(HostFailureKind.OTHER, "More than one reachable Create peripheral has that name")
            }

            CompuktersPeripheralLookupStatus.INVALID_NAME -> {
                KineticsNamedResolution.Failed(HostFailureKind.OTHER, "Invalid peripheral name")
            }

            CompuktersPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED -> {
                KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "Peripheral cable topology limit was exceeded")
            }
        }
    }

    private fun resolveEndpoint(
        level: ServerLevel,
        position: BlockPos,
        kind: PeripheralKind,
    ): KineticsEndpoint? {
        if (!level.hasChunkAt(position)) return null
        return when (val entity = level.getBlockEntity(position)) {
            is SpeedGaugeBlockEntity -> {
                if (kind == PeripheralKind.SPEEDOMETER) {
                    SpeedometerEndpoint(level, position, entity)
                } else {
                    null
                }
            }

            is StressGaugeBlockEntity -> {
                if (kind == PeripheralKind.STRESSOMETER) {
                    StressometerEndpoint(level, position, entity)
                } else {
                    null
                }
            }

            is SpeedControllerBlockEntity -> {
                if (kind == PeripheralKind.ROTATION_CONTROLLER) RotationControllerEndpoint(level, position, entity) else null
            }

            else -> {
                null
            }
        }
    }
}

internal class KineticsHostState(
    private val resolveSide: (Int, PeripheralKind) -> KineticsEndpoint?,
    private val resolveName: (String, PeripheralKind) -> KineticsNamedResolution,
) : CreateCapabilityHandler {
    constructor(resolveSide: (Int, PeripheralKind) -> KineticsEndpoint?) : this(
        resolveSide,
        { _, _ -> KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "No reachable Create peripheral has that name") },
    )

    private val handles = linkedMapOf<Int, KineticsEndpoint>()
    private val handlesByEndpoint = mutableMapOf<Any, Int>()
    private var nextHandle = 1

    override fun acquireSpeedometer(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.SPEEDOMETER)

    override fun speed(argument0: Int): AddonCallResult<Float> = withEndpoint<SpeedometerAccess, Float>(argument0) { it.speed() }

    override fun awaitSpeedChange(argument0: Int): AddonCallResult<Float> {
        val endpoint = endpoint<SpeedometerAccess>(argument0) ?: return endpointFailure()
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        val speedBits = endpoint.speed().toBits()
        return addonPending {
            if (!endpoint.valid()) {
                removeEndpoint(endpoint)
                addonPollFailed(HostFailureKind.INPUT_OUTPUT, STALE_PERIPHERAL_DETAIL)
            } else {
                endpoint.speed().takeIf { it.toBits() != speedBits }?.let(::addonPollCompleted)
            }
        }
    }

    override fun acquireStressometer(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.STRESSOMETER)

    override fun stress(argument0: Int): AddonCallResult<Float> = withEndpoint<StressometerAccess, Float>(argument0) { it.stress() }

    override fun capacity(argument0: Int): AddonCallResult<Float> = withEndpoint<StressometerAccess, Float>(argument0) { it.capacity() }

    override fun awaitStressChange(argument0: Int): AddonCallResult<Unit> {
        val endpoint = endpoint<StressometerAccess>(argument0) ?: return endpointFailure()
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        val stressBits = endpoint.stress().toBits()
        val capacityBits = endpoint.capacity().toBits()
        return addonPending {
            if (!endpoint.valid()) {
                removeEndpoint(endpoint)
                addonPollFailed(HostFailureKind.INPUT_OUTPUT, STALE_PERIPHERAL_DETAIL)
            } else if (endpoint.stress().toBits() != stressBits || endpoint.capacity().toBits() != capacityBits) {
                addonPollCompleted(Unit)
            } else {
                null
            }
        }
    }

    override fun acquireRotationController(argument0: Int): AddonCallResult<Int> = acquire(argument0, PeripheralKind.ROTATION_CONTROLLER)

    override fun targetSpeed(argument0: Int): AddonCallResult<Int> =
        withEndpoint<RotationControllerAccess, Int>(argument0) { it.targetSpeed() }

    override fun setTargetSpeed(
        argument0: Int,
        argument1: Int,
    ): AddonCallResult<Int> = withEndpoint<RotationControllerAccess, Int>(argument0) { it.setTargetSpeed(argument1) }

    override fun acquireRotationControllerByName(argument0: String): AddonCallResult<Int> =
        acquireNamed(argument0, PeripheralKind.ROTATION_CONTROLLER)

    override fun acquireSpeedometerByName(argument0: String): AddonCallResult<Int> = acquireNamed(argument0, PeripheralKind.SPEEDOMETER)

    override fun acquireStressometerByName(argument0: String): AddonCallResult<Int> = acquireNamed(argument0, PeripheralKind.STRESSOMETER)

    override fun reset() {
        handles.clear()
        handlesByEndpoint.clear()
        nextHandle = 1
    }

    private fun acquire(
        side: Int,
        kind: PeripheralKind,
    ): AddonCallResult<Int> {
        if (side !in 0..5) return addonFailed(HostFailureKind.OTHER, "Invalid Create kinetic side")
        val endpoint =
            resolveSide(side, kind)
                ?: return addonFailed(HostFailureKind.UNAVAILABLE, "No matching Create kinetic device is attached on that side")
        return retain(endpoint)
    }

    private fun acquireNamed(
        name: String,
        kind: PeripheralKind,
    ): AddonCallResult<Int> =
        when (val resolution = resolveName(name, kind)) {
            is KineticsNamedResolution.Failed -> addonFailed(resolution.kind, resolution.detail)
            is KineticsNamedResolution.Found -> retain(resolution.endpoint)
        }

    private fun retain(endpoint: KineticsEndpoint): AddonCallResult<Int> {
        val existing = handlesByEndpoint[endpoint.identity]
        if (existing != null) return addonCompleted(existing)
        if (handles.size >= MAXIMUM_HANDLES) {
            return addonFailed(HostFailureKind.UNAVAILABLE, "Create kinetic device handle limit was reached")
        }
        val handle = nextHandle++
        handles[handle] = endpoint
        handlesByEndpoint[endpoint.identity] = handle
        return addonCompleted(handle)
    }

    private inline fun <reified T : KineticsEndpoint, R> withEndpoint(
        handle: Int,
        operation: (T) -> R,
    ): AddonCallResult<R> {
        val endpoint = endpoint<T>(handle) ?: return endpointFailure()
        return if (endpoint.valid()) addonCompleted(operation(endpoint)) else staleEndpoint(endpoint)
    }

    private inline fun <reified T : KineticsEndpoint> endpoint(handle: Int): T? = handles[handle] as? T

    private fun endpointFailure(): AddonCallResult<Nothing> =
        addonFailed(HostFailureKind.UNAVAILABLE, "Create kinetic device handle is unavailable")

    private fun staleEndpoint(endpoint: KineticsEndpoint): AddonCallResult<Nothing> {
        removeEndpoint(endpoint)
        return addonFailed(HostFailureKind.INPUT_OUTPUT, STALE_PERIPHERAL_DETAIL)
    }

    private fun removeEndpoint(endpoint: KineticsEndpoint) {
        val handle = handlesByEndpoint.remove(endpoint.identity) ?: return
        handles.remove(handle)
    }
}

internal enum class PeripheralKind {
    SPEEDOMETER,
    STRESSOMETER,
    ROTATION_CONTROLLER,
    ;

    val deviceKey: String
        get() = name.lowercase()
}

internal sealed interface KineticsNamedResolution {
    data class Found(
        val endpoint: KineticsEndpoint,
    ) : KineticsNamedResolution

    data class Failed(
        val kind: HostFailureKind,
        val detail: String,
    ) : KineticsNamedResolution
}

internal interface KineticsEndpoint {
    val identity: Any

    fun valid(): Boolean
}

internal interface SpeedometerAccess : KineticsEndpoint {
    fun speed(): Float
}

internal interface StressometerAccess : KineticsEndpoint {
    fun stress(): Float

    fun capacity(): Float
}

internal interface RotationControllerAccess : KineticsEndpoint {
    fun targetSpeed(): Int

    fun setTargetSpeed(speed: Int): Int
}

private abstract class CreateEndpoint<T : BlockEntity>(
    protected val level: ServerLevel,
    private val position: BlockPos,
    protected val entity: T,
) : KineticsEndpoint {
    override val identity: Any
        get() = entity

    override fun valid(): Boolean {
        check(level.server.isSameThread) { "Create kinetics must be accessed on the server thread" }
        return !entity.isRemoved && level.hasChunkAt(position) && level.getBlockEntity(position) === entity
    }
}

private class SpeedometerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: SpeedGaugeBlockEntity,
) : CreateEndpoint<SpeedGaugeBlockEntity>(level, position, entity),
    SpeedometerAccess {
    override fun speed(): Float = entity.getSpeed()
}

private class StressometerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: StressGaugeBlockEntity,
) : CreateEndpoint<StressGaugeBlockEntity>(level, position, entity),
    StressometerAccess {
    override fun stress(): Float = entity.getNetworkStress()

    override fun capacity(): Float = entity.getNetworkCapacity()
}

private class RotationControllerEndpoint(
    level: ServerLevel,
    position: BlockPos,
    entity: SpeedControllerBlockEntity,
) : CreateEndpoint<SpeedControllerBlockEntity>(level, position, entity),
    RotationControllerAccess {
    override fun targetSpeed(): Int = entity.targetSpeed.getValue()

    override fun setTargetSpeed(speed: Int): Int {
        entity.targetSpeed.setValue(speed)
        return entity.targetSpeed.getValue()
    }
}

private const val MAXIMUM_HANDLES = 64
private const val STALE_PERIPHERAL_DETAIL = "Create kinetic device was removed, replaced, or unloaded"
