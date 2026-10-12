/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.redstone

import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.addon.ResourceCheckpointReader
import ru.lazyhat.compukters.api.addon.ResourceCheckpointWriter
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralHandleLease
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

interface RedstonePortEndpoint : PeripheralHandleLease {
    val signals: RedstonePortState
}

/** One program's asynchronous waits. Physical signal state belongs to the port. */
class RedstonePortHost(
    private val resolve: (Int) -> RedstonePortEndpoint,
    private val release: (Int) -> Unit,
) : ProgramAddonHost {
    override val capabilitySchemas = listOf(SCHEMA)

    private data class Wait(
        val handle: Int,
        val side: Int,
        val mode: Int,
        val level: Int,
        val revision: Long,
    )

    private val pending = linkedMapOf<VmHostRequestIdentity, Wait?>()

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch =
        try {
            val counts = intArrayOf(2, 3, 2, 4, 1, 2)
            if (request.capability != SCHEMA.identity || request.operation !in counts.indices ||
                request.arguments.size != counts[request.operation]
            ) {
                malformed()
            }
            val values = request.arguments.map { (it as? VmValue.I32)?.value ?: malformed() }
            val handle = values[0]
            if (request.operation == 4) {
                release(handle)
                ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
            } else if (request.operation == 5) {
                require(values[1] in 0..31)
                val endpoint = resolve(handle)
                endpoint.signals.setAll(endpoint, values[1])
                ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
            } else {
                val side = values[1]
                require(side in 0..5)
                val endpoint = resolve(handle)
                val state = endpoint.signals
                when (request.operation) {
                    0 -> {
                        ProgramAddonDispatch.Completed(HostResponse.IntSuccess(state.input(side)))
                    }

                    1 -> {
                        require(values[2] in 0..31)
                        state.set(endpoint, side, values[2])
                        ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                    }

                    else -> {
                        val mode = if (request.operation == 2) 0 else values[2]
                        val level = if (request.operation == 2) 0 else values[3]
                        require(mode in 0..2 && level in 0..15 && (request.operation == 2 || mode != 0))
                        val wait = Wait(handle, side, mode, level, state.revision(side))
                        val response = ready(wait, state)
                        if (response != null) {
                            ProgramAddonDispatch.Completed(response)
                        } else {
                            if (pending.size >=
                                MAXIMUM_PENDING
                            ) {
                                throw PeripheralFailure(HostFailureKind.UNAVAILABLE, "Too many redstone input waits")
                            }
                            check(request.identity !in pending)
                            pending[request.identity] = wait
                            ProgramAddonDispatch.Pending
                        }
                    }
                }
            }
        } catch (failure: PeripheralFailure) {
            ProgramAddonDispatch.Completed(HostResponse.Failure(failure.kind, failure.message))
        } catch (_: IllegalArgumentException) {
            ProgramAddonDispatch.Completed(HostResponse.Failure(HostFailureKind.OTHER, "Malformed redstone port request"))
        }

    override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0)
        val completions = mutableListOf<ProgramAddonCompletion>()
        val iterator = pending.iterator()
        while (iterator.hasNext() && completions.size < maximumCompletions) {
            val (identity, wait) = iterator.next()
            val response =
                try {
                    if (wait == null) throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Redstone port wait interrupted by hibernation")
                    ready(wait, resolve(wait.handle).signals)
                } catch (failure: PeripheralFailure) {
                    HostResponse.Failure(failure.kind, failure.message)
                }
            if (response != null) {
                iterator.remove()
                completions += ProgramAddonCompletion(identity, response)
            }
        }
        return completions
    }

    private fun ready(
        wait: Wait,
        state: RedstonePortState,
    ): HostResponse? {
        val level = state.input(wait.side)
        val ready =
            when (wait.mode) {
                0 -> state.revision(wait.side) != wait.revision
                1 -> level == wait.level
                else -> level >= wait.level
            }
        return if (ready) HostResponse.IntSuccess(level) else null
    }

    override fun checkpoint(): ByteArray =
        ResourceCheckpointWriter()
            .apply {
                int(1)
                int(pending.size)
                pending.keys.forEach { identity ->
                    int(identity.taskId)
                    long(identity.requestId)
                }
            }.finish()

    override fun restoreCheckpoint(state: ByteArray) {
        val reader = ResourceCheckpointReader(state)
        require(reader.int() == 1)
        val restored = linkedMapOf<VmHostRequestIdentity, Wait?>()
        repeat(reader.count(MAXIMUM_PENDING)) {
            val identity = VmHostRequestIdentity(reader.int(), reader.long())
            require(!restored.containsKey(identity))
            restored[identity] = null
        }
        reader.finish()
        pending.clear()
        pending.putAll(restored)
    }

    override fun reset() {
        pending.clear()
    }

    private fun malformed(): Nothing = throw PeripheralFailure(HostFailureKind.OTHER, "Malformed redstone port request")

    companion object {
        private const val MAXIMUM_PENDING = 64
        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "redstone-port", 1, 0),
                listOf(
                    HostOperationSchema(listOf(HostValueType.I32, HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(listOf(HostValueType.I32, HostValueType.I32, HostValueType.I32), HostValueType.UNIT, true),
                    HostOperationSchema(listOf(HostValueType.I32, HostValueType.I32), HostValueType.I32, true),
                    HostOperationSchema(
                        listOf(HostValueType.I32, HostValueType.I32, HostValueType.I32, HostValueType.I32),
                        HostValueType.I32,
                        true,
                    ),
                    HostOperationSchema(listOf(HostValueType.I32), HostValueType.UNIT, true),
                    HostOperationSchema(listOf(HostValueType.I32, HostValueType.I32), HostValueType.UNIT, true),
                ),
            )
    }
}
