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

    /** Null keeps atomic turn effects. Otherwise acknowledge input independently before the next grant. */
    fun submitRedstoneInput(
        endpoint: VmActorEndpoint,
        packet: Int,
    ): Boolean? = null

    /** Admission of one frame; the observer remains bound across later host waits and reports. */
    fun submitFrame(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        observer: ProgramRuntimeActorObserver,
    ): VmActorSubmission

    fun recordHostContinuationDelay(delayTicks: Long)

    fun recordCoalescedRedstoneInput()
}
