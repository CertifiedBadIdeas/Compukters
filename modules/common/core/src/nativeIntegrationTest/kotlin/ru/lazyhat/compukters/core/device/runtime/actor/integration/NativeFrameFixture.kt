/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor.integration

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.core.device.runtime.actor.ActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorEffect
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorObservation
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorObserver
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeHostCompletion
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore

/** Fixed capacity owner for focused native lifecycle tests; Rust alone consumes frame credit. */
internal class NativeFrameFixture(
    val native: NativeFleetActorRuntimeService,
) : ActorRuntimeService,
    AutoCloseable {
    private data class Frame(
        val tick: Long,
        val retirements: Int,
    )

    private val plans = mutableMapOf<VmActorEndpoint, Frame>()

    fun configureFrame(
        endpoint: VmActorEndpoint,
        tick: Long,
        retirements: Int,
    ): Boolean {
        if (!native.contains(endpoint) || tick <= (plans[endpoint]?.tick ?: -1L)) return false
        plans[endpoint] = Frame(tick, retirements)
        return true
    }

    private val submittedTicks = mutableMapOf<VmActorEndpoint, Long>()
    private val observedTicks = mutableMapOf<VmActorEndpoint, Long>()

    fun previousFrameObserved(endpoint: VmActorEndpoint): Boolean = (observedTicks[endpoint] ?: -1L) >= (submittedTicks[endpoint] ?: -1L)

    override val redstoneInputCapacity get() = native.redstoneInputCapacity

    override fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ) = native.request(endpoint, command)

    override fun submitHostCompletions(
        endpoint: VmActorEndpoint,
        completions: List<ProgramRuntimeHostCompletion>,
    ) = native.submitHostCompletions(endpoint, completions)

    override fun submitRedstoneInput(
        endpoint: VmActorEndpoint,
        packet: Int,
    ) = native.submitRedstoneInput(endpoint, packet)

    override fun submitFrame(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        observer: ProgramRuntimeActorObserver,
    ): VmActorSubmission {
        val plan = plans[endpoint]?.takeIf { it.tick == worldTick } ?: return VmActorSubmission.PERMIT_PENDING
        val tracking =
            object : ProgramRuntimeActorObserver by observer {
                override fun updated(observation: ProgramRuntimeActorObservation) {
                    observedTicks[endpoint] = maxOf(observedTicks[endpoint] ?: -1L, observation.worldTick)
                    observer.updated(observation)
                }
            }
        val result =
            native.submitFrame(
                endpoint,
                worldTick,
                plan.retirements,
                1_000_000_000L,
                System.nanoTime() + 30_000_000_000L,
                effects,
                tracking,
            )
        if (result == VmActorSubmission.ACCEPTED) submittedTicks[endpoint] = worldTick
        return result
    }

    override fun recordHostContinuationDelay(delayTicks: Long) = native.recordHostContinuationDelay(delayTicks)

    override fun recordCoalescedRedstoneInput() = native.recordCoalescedRedstoneInput()

    fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
    ) = native.attach(endpoint, host)

    fun attach(
        endpoint: VmActorEndpoint,
        machine: Long,
        host: ProgramRuntimeHost,
    ) = native.attach(endpoint, machine, host)

    fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        image: VmSystemImage,
        compilerRouter: CompilerCompletionRouter? = null,
    ) = native.attachBootable(endpoint, store, image, compilerRouter = compilerRouter)

    fun register(
        endpoint: VmActorEndpoint,
        machine: Long,
        host: ProgramRuntimeHost,
    ) = native.register(endpoint, machine, host)

    fun pump(
        events: Int,
        commands: Int,
        deadline: Long = Long.MAX_VALUE,
    ) = native.pump(events, commands, deadline)

    fun stop(endpoint: VmActorEndpoint) = native.stop(endpoint)

    override fun close() = native.close()
}

internal abstract class RecordingActorObserver : ProgramRuntimeActorObserver {
    override fun addonAction(action: ProgramAddonAction) = Unit

    abstract fun complete(
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
    )

    private var observed = false

    override fun updated(observation: ProgramRuntimeActorObservation) {
        observed = true
        complete(
            ProgramRuntimeActorReply(
                ProgramRuntimeRequestId(1),
                observation.state,
                ProgramRuntimeActorValue.None,
                observation.fileSystemGeneration,
                observation.retiredInstructions,
            ),
            null,
        )
    }

    override fun failed(failure: Throwable) {
        if (observed && failure is java.util.concurrent.CancellationException) return
        complete(null, failure)
    }
}

internal fun ActorRuntimeService.submitFrame(
    endpoint: VmActorEndpoint,
    tick: Long,
    effects: List<ProgramRuntimeActorEffect>,
    completion: (ProgramRuntimeActorReply?, Throwable?) -> Unit,
) = submitFrame(
    endpoint,
    tick,
    effects,
    object : RecordingActorObserver() {
        override fun complete(
            reply: ProgramRuntimeActorReply?,
            failure: Throwable?,
        ) = completion(reply, failure)
    },
)
