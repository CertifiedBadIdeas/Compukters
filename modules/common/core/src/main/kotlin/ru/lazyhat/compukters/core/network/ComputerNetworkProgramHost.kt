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

package ru.lazyhat.compukters.core.network

import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.addon.ResourceCheckpointReader
import ru.lazyhat.compukters.api.addon.ResourceCheckpointWriter
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

/** One program's pending receives; messages belong to the physical computer inbox. */
class ComputerNetworkProgramHost(
    private val endpoint: ComputerNetworkEndpoint,
) : ProgramAddonHost {
    override val capabilitySchemas = listOf(SCHEMA)
    private val pending = linkedMapOf<VmHostRequestIdentity, ComputerNetworkPort?>()

    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
        if (request.capability != SCHEMA.identity || request.operation !in 0..2 ||
            request.arguments.size != (if (request.operation == 1) 1 else 0)
        ) {
            return ProgramAddonDispatch.Completed(HostResponse.Failure(HostFailureKind.OTHER, "Malformed network request"))
        }
        return try {
            val port = endpoint.connection()?.takeIf { it.connected }
            if (request.operation == 0) {
                ProgramAddonDispatch.Completed(HostResponse.BoolSuccess(port != null))
            } else {
                if (port == null) throw ComputerNetworkFailure("Computer cable connection is unavailable")
                when (request.operation) {
                    1 -> {
                        val message =
                            (request.arguments.single() as? VmValue.ByteArrayValue)?.value
                                ?: return ProgramAddonDispatch.Completed(
                                    HostResponse.Failure(HostFailureKind.OTHER, "Malformed network payload"),
                                )
                        port.send(message)
                        ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                    }

                    else -> {
                        val message = port.receive()
                        if (message != null) {
                            ProgramAddonDispatch.Completed(HostResponse.ByteArraySuccess(message))
                        } else {
                            if (pending.size >= MAXIMUM_PENDING) throw ComputerNetworkFailure("Too many waiting network receives")
                            check(request.identity !in pending)
                            pending[request.identity] = port
                            ProgramAddonDispatch.Pending
                        }
                    }
                }
            }
        } catch (failure: ComputerNetworkFailure) {
            ProgramAddonDispatch.Completed(failure(failure.message!!))
        }
    }

    override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0)
        val completions = mutableListOf<ProgramAddonCompletion>()
        val iterator = pending.iterator()
        while (iterator.hasNext() && completions.size < maximumCompletions) {
            val (identity, port) = iterator.next()
            val response =
                try {
                    if (port == null || !port.connected) throw ComputerNetworkFailure("Computer cable connection was interrupted")
                    port.receive()?.let(HostResponse::ByteArraySuccess)
                } catch (caught: ComputerNetworkFailure) {
                    failure(caught.message!!)
                }
            if (response != null) {
                iterator.remove()
                completions += ProgramAddonCompletion(identity, response)
            }
        }
        return completions
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
        val restored = linkedMapOf<VmHostRequestIdentity, ComputerNetworkPort?>()
        repeat(reader.count(MAXIMUM_PENDING)) {
            val identity = VmHostRequestIdentity(reader.int(), reader.long())
            require(!restored.containsKey(identity))
            // Queues are volatile; resuming a saved receive reports interruption, never a new peer's data.
            restored[identity] = null
        }
        reader.finish()
        pending.clear()
        pending.putAll(restored)
    }

    override fun reset() {
        pending.clear()
    }

    private fun failure(detail: String) = HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, detail)

    companion object {
        private const val MAXIMUM_PENDING = 64
        val SCHEMA =
            HostCapabilitySchema(
                CapabilityIdentity("compukters", "network", 1, 0),
                listOf(
                    HostOperationSchema(emptyList(), HostValueType.BOOL, true),
                    HostOperationSchema(listOf(HostValueType.BYTE_ARRAY), HostValueType.UNIT, true),
                    HostOperationSchema(emptyList(), HostValueType.BYTE_ARRAY, true),
                ),
            )
    }
}
