/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture

/** Server lifetime and capacity boundary. World effects remain on the carrier's owner thread. */
abstract class ProgramRuntimeService : ProgramRuntimeLifecycle {
    internal abstract val carrierRuntime: ActorRuntimeService

    abstract fun attachStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): ProgramRuntimeActorLease?

    fun registerStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): Boolean = attachStandalone(endpoint, tickBudget) != null

    abstract fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        systemImage: VmSystemImage,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
        addonHost: ProgramAddonHost? = null,
    ): ProgramRuntimeActorLease?

    abstract fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply>

    open fun turn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect> = emptyList(),
    ): CompletableFuture<ProgramRuntimeActorReply> {
        val future = CompletableFuture<ProgramRuntimeActorReply>()
        val admission =
            carrierRuntime.submitTurn(endpoint, worldTick, effects) { reply, failure ->
                if (failure == null) future.complete(checkNotNull(reply)) else future.completeExceptionally(failure)
            }
        if (admission != VmActorSubmission.ACCEPTED) future.completeExceptionally(ProgramRuntimeActorRequestException(admission))
        return future
    }
}
