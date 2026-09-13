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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonCompletion
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonDispatch
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock

object CreateKineticsIntegration {
    fun register() {
        ComputerAddonHosts.register(
            factory = { level, position, _ -> CreateKineticsHost.create(level, position) },
            guestApiBundles = listOf(CREATE_KINETICS_GUEST_API),
        )
    }
}

internal class CreateKineticsHost private constructor(
    private val state: KineticsHostState,
) : ProgramAddonHost {
    override val capabilitySchemas: List<HostCapabilitySchema> = listOf(CREATE_KINETICS_SCHEMA)

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch = state.dispatch(request)

    override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> = state.poll(maximumCompletions)

    override fun reset() = state.reset()

    companion object {
        fun create(
            level: ServerLevel,
            computerPosition: BlockPos,
        ): CreateKineticsHost =
            CreateKineticsHost(
                KineticsHostState { side, kind ->
                    check(level.server.isSameThread) { "Create kinetics must be accessed on the server thread" }
                    val direction = adjacentDirection(level, computerPosition, side) ?: return@KineticsHostState null
                    val position = computerPosition.relative(direction)
                    if (!level.hasChunkAt(position)) return@KineticsHostState null
                    when (val entity = level.getBlockEntity(position)) {
                        is SpeedGaugeBlockEntity -> {
                            if (kind ==
                                PeripheralKind.SPEEDOMETER
                            ) {
                                SpeedometerEndpoint(level, position, entity)
                            } else {
                                null
                            }
                        }

                        is StressGaugeBlockEntity -> {
                            if (kind ==
                                PeripheralKind.STRESSOMETER
                            ) {
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
                },
            )
    }
}

internal class KineticsHostState(
    private val resolve: (Int, PeripheralKind) -> KineticsEndpoint?,
) {
    private val handles = linkedMapOf<Int, KineticsEndpoint>()
    private val handlesByEndpoint = mutableMapOf<Any, Int>()
    private val pending = linkedMapOf<VmHostRequestIdentity, PendingKineticsRequest>()
    private var nextHandle = 1

    fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        if (request.capability != CREATE_KINETICS_IDENTITY) return completed(HostFailureKind.UNAVAILABLE, FAILURE_WRONG_CAPABILITY)
        return when (request.operation) {
            0 -> acquire(request, PeripheralKind.SPEEDOMETER)
            1 -> withEndpoint<SpeedometerAccess>(request) { HostResponse.FloatSuccess(it.speed()) }
            2 -> awaitSpeed(request)
            3 -> acquire(request, PeripheralKind.STRESSOMETER)
            4 -> withEndpoint<StressometerAccess>(request) { HostResponse.FloatSuccess(it.stress()) }
            5 -> withEndpoint<StressometerAccess>(request) { HostResponse.FloatSuccess(it.capacity()) }
            6 -> awaitStress(request)
            7 -> acquire(request, PeripheralKind.ROTATION_CONTROLLER)
            8 -> withEndpoint<RotationControllerAccess>(request) { HostResponse.IntSuccess(it.targetSpeed()) }
            9 -> setTargetSpeed(request)
            else -> completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        }
    }

    fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0) { "maximum addon completions must be positive" }
        val completions = mutableListOf<ProgramAddonCompletion>()
        val iterator = pending.iterator()
        while (iterator.hasNext() && completions.size < maximumCompletions) {
            val (identity, wait) = iterator.next()
            val response = wait.poll() ?: continue
            iterator.remove()
            if (response == HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, FAILURE_STALE_PERIPHERAL)) {
                removeEndpoint(wait.endpoint)
            }
            completions += ProgramAddonCompletion(identity, response)
        }
        return completions
    }

    fun reset() {
        handles.clear()
        handlesByEndpoint.clear()
        pending.clear()
        nextHandle = 1
    }

    private fun acquire(
        request: ProgramAddonRequest,
        kind: PeripheralKind,
    ): ProgramAddonDispatch {
        val side = request.singleIntArgument() ?: return completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        if (side !in 0..5) return completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        val endpoint = resolve(side, kind) ?: return completed(HostFailureKind.UNAVAILABLE, FAILURE_MISSING_PERIPHERAL)
        val existing = handlesByEndpoint[endpoint.identity]
        if (existing != null) return ProgramAddonDispatch.Completed(HostResponse.IntSuccess(existing))
        if (handles.size >= MAXIMUM_HANDLES) return completed(HostFailureKind.UNAVAILABLE, FAILURE_HANDLE_LIMIT)
        val handle = nextHandle++
        handles[handle] = endpoint
        handlesByEndpoint[endpoint.identity] = handle
        return ProgramAddonDispatch.Completed(HostResponse.IntSuccess(handle))
    }

    private inline fun <reified T : KineticsEndpoint> withEndpoint(
        request: ProgramAddonRequest,
        operation: (T) -> HostResponse,
    ): ProgramAddonDispatch {
        val endpoint = endpoint<T>(request) ?: return endpointFailure(request)
        return if (endpoint.valid()) ProgramAddonDispatch.Completed(operation(endpoint)) else staleEndpoint(endpoint)
    }

    private inline fun <reified T : KineticsEndpoint> endpoint(request: ProgramAddonRequest): T? {
        val handle = request.singleIntArgument() ?: return null
        return handles[handle] as? T
    }

    private fun endpointFailure(request: ProgramAddonRequest): ProgramAddonDispatch =
        if (request.singleIntArgument() == null) {
            completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        } else {
            completed(HostFailureKind.UNAVAILABLE, FAILURE_UNKNOWN_HANDLE)
        }

    private fun staleEndpoint(endpoint: KineticsEndpoint): ProgramAddonDispatch {
        removeEndpoint(endpoint)
        return completed(HostFailureKind.INPUT_OUTPUT, FAILURE_STALE_PERIPHERAL)
    }

    private fun awaitSpeed(request: ProgramAddonRequest): ProgramAddonDispatch {
        val endpoint = endpoint<SpeedometerAccess>(request) ?: return endpointFailure(request)
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        return await(request.identity, SpeedWait(endpoint, endpoint.speed().toBits()))
    }

    private fun awaitStress(request: ProgramAddonRequest): ProgramAddonDispatch {
        val endpoint = endpoint<StressometerAccess>(request) ?: return endpointFailure(request)
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        return await(request.identity, StressWait(endpoint, endpoint.stress().toBits(), endpoint.capacity().toBits()))
    }

    private fun await(
        identity: VmHostRequestIdentity,
        wait: PendingKineticsRequest,
    ): ProgramAddonDispatch {
        if (pending.size >= MAXIMUM_PENDING_WAITS || identity in pending) {
            return completed(HostFailureKind.UNAVAILABLE, FAILURE_WAIT_LIMIT)
        }
        pending[identity] = wait
        return ProgramAddonDispatch.Pending
    }

    private fun setTargetSpeed(request: ProgramAddonRequest): ProgramAddonDispatch {
        val arguments = request.arguments
        if (arguments.size != 2) return completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        val handle = (arguments[0] as? VmValue.I32)?.value ?: return completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        val speed = (arguments[1] as? VmValue.I32)?.value ?: return completed(HostFailureKind.OTHER, FAILURE_MALFORMED_REQUEST)
        val endpoint = handles[handle] as? RotationControllerAccess ?: return completed(HostFailureKind.UNAVAILABLE, FAILURE_UNKNOWN_HANDLE)
        if (!endpoint.valid()) return staleEndpoint(endpoint)
        return ProgramAddonDispatch.Completed(HostResponse.IntSuccess(endpoint.setTargetSpeed(speed)))
    }

    private fun removeEndpoint(endpoint: KineticsEndpoint) {
        val handle = handlesByEndpoint.remove(endpoint.identity) ?: return
        handles.remove(handle)
    }

    private fun ProgramAddonRequest.singleIntArgument(): Int? =
        arguments.singleOrNull()?.let { argument -> (argument as? VmValue.I32)?.value }

    private fun completed(
        kind: HostFailureKind,
        code: Long,
    ) = ProgramAddonDispatch.Completed(HostResponse.Failure(kind, code))
}

internal enum class PeripheralKind {
    SPEEDOMETER,
    STRESSOMETER,
    ROTATION_CONTROLLER,
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

private sealed interface PendingKineticsRequest {
    val endpoint: KineticsEndpoint

    fun poll(): HostResponse?
}

private class SpeedWait(
    override val endpoint: SpeedometerAccess,
    private val speedBits: Int,
) : PendingKineticsRequest {
    override fun poll(): HostResponse? {
        if (!endpoint.valid()) return HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, FAILURE_STALE_PERIPHERAL)
        val speed = endpoint.speed()
        return if (speed.toBits() != speedBits) HostResponse.FloatSuccess(speed) else null
    }
}

private class StressWait(
    override val endpoint: StressometerAccess,
    private val stressBits: Int,
    private val capacityBits: Int,
) : PendingKineticsRequest {
    override fun poll(): HostResponse? {
        if (!endpoint.valid()) return HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, FAILURE_STALE_PERIPHERAL)
        return if (endpoint.stress().toBits() != stressBits || endpoint.capacity().toBits() != capacityBits) {
            HostResponse.UnitSuccess
        } else {
            null
        }
    }
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

private fun adjacentDirection(
    level: ServerLevel,
    computerPosition: BlockPos,
    side: Int,
): Direction? {
    val state = level.getBlockState(computerPosition)
    if (!state.hasProperty(ComputerBlock.FACING)) return null
    val facing = state.getValue(ComputerBlock.FACING)
    return when (side) {
        0 -> facing
        1 -> facing.opposite
        2 -> facing.counterClockWise
        3 -> facing.clockWise
        4 -> Direction.UP
        5 -> Direction.DOWN
        else -> null
    }
}

private val CREATE_KINETICS_IDENTITY = CapabilityIdentity("create", "kinetics", 1, 0)
private val CREATE_KINETICS_SCHEMA =
    HostCapabilitySchema(
        CREATE_KINETICS_IDENTITY,
        listOf(
            operation(HostValueType.I32, HostValueType.I32),
            operation(HostValueType.I32, HostValueType.F32),
            operation(HostValueType.I32, HostValueType.F32),
            operation(HostValueType.I32, HostValueType.I32),
            operation(HostValueType.I32, HostValueType.F32),
            operation(HostValueType.I32, HostValueType.F32),
            operation(HostValueType.I32, HostValueType.UNIT),
            operation(HostValueType.I32, HostValueType.I32),
            operation(HostValueType.I32, HostValueType.I32),
            operation(listOf(HostValueType.I32, HostValueType.I32), HostValueType.I32),
        ),
    )

private fun operation(
    argument: HostValueType,
    result: HostValueType,
) = operation(listOf(argument), result)

private fun operation(
    arguments: List<HostValueType>,
    result: HostValueType,
) = HostOperationSchema(arguments, result, asynchronous = true)

private const val CREATE_KINETICS_MODULE = "create:kinetics"
private val CREATE_KINETICS_GUEST_API =
    AddonGuestApiBundleCodec
        .decode(
            checkNotNull(CreateKineticsIntegration::class.java.getResourceAsStream("/META-INF/compukters/addons/create-kinetics.cagb")) {
                "packaged Create kinetics Guest API bundle is missing"
            }.use { it.readBytes() },
        ).also { bundle -> require(bundle.identity.module == CREATE_KINETICS_MODULE) }
private const val MAXIMUM_HANDLES = 64
private const val MAXIMUM_PENDING_WAITS = 64
private const val FAILURE_WRONG_CAPABILITY = 1L
private const val FAILURE_MALFORMED_REQUEST = 2L
private const val FAILURE_MISSING_PERIPHERAL = 3L
private const val FAILURE_UNKNOWN_HANDLE = 4L
private const val FAILURE_STALE_PERIPHERAL = 5L
private const val FAILURE_HANDLE_LIMIT = 6L
private const val FAILURE_WAIT_LIMIT = 7L
