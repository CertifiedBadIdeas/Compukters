/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import java.util.concurrent.CompletableFuture

/** The owner-thread contract needed by an in-world computer carrier. */
internal interface ActorRuntimeService {
    val redstoneInputCapacity: Int

    fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply>

    /** Null keeps atomic turn delivery. Otherwise admit an ordered prefix independently of Guest credit. */
    fun submitHostCompletions(
        endpoint: VmActorEndpoint,
        completions: List<ProgramRuntimeHostCompletion>,
    ): Int? = null

    fun submitTurn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        completion: ProgramRuntimeTurnCompletion,
    ): VmActorSubmission

    fun continueTurn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        completion: ProgramRuntimeTurnCompletion,
    ): Boolean

    fun recordHostContinuationDelay(delayTicks: Long)

    fun recordCoalescedRedstoneInput()
}
