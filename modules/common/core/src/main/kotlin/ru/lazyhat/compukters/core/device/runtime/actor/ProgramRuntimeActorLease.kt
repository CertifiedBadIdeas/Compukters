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
    private val closed: CompletableFuture<Long?>,
    private val prepareHibernation: (ProgramActorHibernation) -> Unit,
    private val unregister: () -> CompletableFuture<Boolean>,
) {
    private var closing: CompletableFuture<Long?>? = null

    /** Does not require server result pumping; callbacks may execute on the closing worker. */
    fun closeAsync(): CompletableFuture<Long?> = finishClose(null)

    internal fun hibernateAsync(request: ProgramActorHibernation): CompletableFuture<Long?> = finishClose(request)

    @Synchronized
    private fun finishClose(request: ProgramActorHibernation?): CompletableFuture<Long?> {
        closing?.let { return it.copy() }
        if (request != null) prepareHibernation(request)
        return unregister().thenCompose { closed }.also { closing = it }.copy()
    }
}

internal class ProgramActorHibernation(
    val worldTick: Long,
    addonState: ByteArray,
    effects: List<ProgramRuntimeActorEffect>,
) {
    val addonState = addonState.copyOf()
    val effects = effects.toList()

    init {
        require(worldTick >= 0 && this.addonState.size <= 1024 * 1024)
        require(this.effects.size <= 3)
        require(this.effects.none { it is ProgramRuntimeActorEffect.RedstoneInput })
    }
}
