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

package ru.lazyhat.compukters.api.addon

import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

/** Server-thread boundary for optional, loader-independent computer peripherals. */
interface ProgramAddonHost : AutoCloseable {
    val capabilitySchemas: List<HostCapabilitySchema>

    fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch

    fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> {
        require(maximumCompletions > 0) { "maximum addon completions must be positive" }
        return emptyList()
    }

    /** A computer-wide, non-reused ID; 0 denotes the root program. */
    fun programStarted(programId: Long) = Unit

    /** Default cleanup for a host without independently retained parent scopes. */
    fun programStopped(programId: Long) = reset()

    /** Server-thread resource descriptions; pending operations are cancelled when the host closes. */
    fun checkpoint(): ByteArray = throw UnsupportedOperationException("Addon host does not support hibernation")

    /** Rebind exact resources before restored Guest execution is enabled. */
    fun restoreCheckpoint(state: ByteArray) {
        require(state.isEmpty()) { "Addon host checkpoint is unsupported" }
    }

    fun restoreCheckpoint(
        state: ByteArray,
        programScopes: List<Long>,
    ) {
        require(programScopes == listOf(0L)) { "Addon host does not support saved process scopes" }
        restoreCheckpoint(state)
    }

    fun reset()

    override fun close() = reset()
}

class ProgramAddonRequest
    @JvmOverloads
    constructor(
        val identity: VmHostRequestIdentity,
        val capability: CapabilityIdentity,
        val operation: Int,
        arguments: List<VmValue>,
        val programId: Long = 0,
    ) {
        val arguments: List<VmValue> = arguments.toList()

        init {
            require(programId >= 0) { "addon program id must not be negative" }
            require(operation >= 0) { "addon operation must not be negative" }
        }
    }

data class ProgramAddonCompletion(
    val identity: VmHostRequestIdentity,
    val response: HostResponse,
)

sealed interface ProgramAddonDispatch {
    data class Completed(
        val response: HostResponse,
    ) : ProgramAddonDispatch

    data object Pending : ProgramAddonDispatch
}

/** Ordered world-thread actions emitted by the runtime, including requests from nested programs. */
sealed interface ProgramAddonAction {
    data class Request(
        val request: ProgramAddonRequest,
    ) : ProgramAddonAction

    data class Started(
        val programId: Long,
    ) : ProgramAddonAction

    data class Stopped(
        val programId: Long,
    ) : ProgramAddonAction
}
