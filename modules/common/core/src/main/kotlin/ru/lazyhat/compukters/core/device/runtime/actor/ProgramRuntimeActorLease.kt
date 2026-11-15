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

package ru.lazyhat.compukters.core.device.runtime.actor

import java.util.concurrent.CompletableFuture

/** The close result is produced by the worker after draining accepted work and closing native resources. */
class ProgramRuntimeActorLease internal constructor(
    val endpoint: VmActorEndpoint,
    prepareClose: (ProgramActorHibernation?) -> ProgramActorCloseReservation,
) {
    internal constructor(
        endpoint: VmActorEndpoint,
        closed: CompletableFuture<Long?>,
        prepareHibernation: (ProgramActorHibernation) -> Unit,
        unregister: () -> CompletableFuture<Boolean>,
    ) : this(endpoint, { request ->
        ProgramActorCloseReservation {
            if (request != null) prepareHibernation(request)
            unregister().thenCompose { closed }
        }
    })

    private var prepareClose: ((ProgramActorHibernation?) -> ProgramActorCloseReservation)? = prepareClose
    private var closing: ProgramActorCloseReservation? = null

    /** Does not require server result pumping; callbacks may execute on the closing worker. */
    fun closeAsync(): CompletableFuture<Long?> = reserveClose(null).commit()

    internal fun hibernateAsync(request: ProgramActorHibernation): CompletableFuture<Long?> = reserveClose(request).commit()

    /** Rejection must happen before returning a reservation, without canceling live carrier work. */
    @Synchronized
    internal fun reserveClose(request: ProgramActorHibernation?): ProgramActorCloseReservation {
        closing?.let { return it }
        val accepted = checkNotNull(prepareClose)(request)
        closing = accepted
        prepareClose = null
        return accepted
    }
}

/** Admission is side-effect-free for the carrier; commit is idempotent, including reentrant calls. */
internal class ProgramActorCloseReservation(
    commitClose: () -> CompletableFuture<Long?>,
) {
    private var commitClose: (() -> CompletableFuture<Long?>)? = commitClose
    private val completed = CompletableFuture<Long?>()

    fun result(): CompletableFuture<Long?> = completed.copy()

    @Synchronized
    fun commit(): CompletableFuture<Long?> {
        val action = commitClose ?: return completed.copy()
        commitClose = null
        try {
            action().whenComplete { generation, failure ->
                if (failure == null) completed.complete(generation) else completed.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            completed.completeExceptionally(failure)
        }
        return completed.copy()
    }
}

internal class ProgramActorHibernation(
    val worldTick: Long,
    addonState: ByteArray,
    effects: List<ProgramRuntimeActorEffect>,
    val saveExecution: Boolean = true,
) {
    val addonState = addonState.copyOf()
    val effects = effects.toList()

    init {
        require(worldTick >= 0 && this.addonState.size <= 1024 * 1024)
        require(this.effects.size <= 3)
        require(this.effects.none { it is ProgramRuntimeActorEffect.RedstoneInput })
    }
}
