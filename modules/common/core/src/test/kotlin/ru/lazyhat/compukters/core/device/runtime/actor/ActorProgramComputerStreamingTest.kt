/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActorProgramComputerStreamingTest {
    @Test
    fun `streamed world result waits for turn settlement and is sent once`() {
        val service = Service()
        var dispatched = 0
        val addon =
            object : TestAddon() {
                override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
                    dispatched++
                    return ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                }
            }
        val carrier = computer(service, addon)
        carrier.turnOn().join()
        carrier.serverTick(4)
        val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
        observer.addonAction(request(1))
        assertEquals(1, dispatched)
        assertEquals(0, service.continuations.size)
        observer.complete(reply(), null)
        val effect = assertIs<ProgramRuntimeActorEffect.CompleteAddons>(service.continuations.single().single())
        assertEquals(listOf(VmHostRequestIdentity(1, 1)), effect.completions.map { it.identity })
        checkNotNull(service.completion).complete(reply(), null)
        assertEquals(1, service.continuations.size)
        carrier.closeAsync().join()
    }

    @Test
    fun `reentrant addon close fences streamed and final reply actions`() {
        for (streaming in listOf(false, true)) {
            val service = Service()
            lateinit var carrier: ActorProgramComputer
            var dispatched = 0
            val addon =
                object : TestAddon() {
                    override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
                        dispatched++
                        carrier.closeAsync()
                        return ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                    }
                }
            carrier = computer(service, addon)
            carrier.turnOn().join()
            carrier.serverTick(4)
            val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
            if (streaming) {
                observer.addonAction(request(1))
                observer.addonAction(request(2))
                observer.complete(reply(), null)
            } else {
                observer.complete(reply().copy(addonActions = listOf(request(1), request(2))), null)
            }
            assertEquals(1, dispatched)
            assertTrue(service.continuations.isEmpty())
            assertEquals(ProgramRuntimeState.Closed, carrier.state)
        }
    }

    @Test
    fun `streamed child exit discards only child completions before continuation`() {
        val service = Service()
        val lifecycle = mutableListOf<Long>()
        val addon =
            object : TestAddon() {
                override fun dispatch(request: ProgramAddonRequest) = ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)

                override fun programStarted(programId: Long) {
                    lifecycle += programId
                }

                override fun programStopped(programId: Long) {
                    lifecycle += -programId
                }
            }
        val carrier = computer(service, addon)
        carrier.turnOn().join()
        carrier.serverTick(4)
        val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
        observer.addonAction(request(1))
        observer.addonAction(ProgramAddonAction.Started(1))
        observer.addonAction(request(2, 1))
        observer.addonAction(ProgramAddonAction.Stopped(1))
        observer.complete(reply(), null)
        assertEquals(listOf(1L, -1L), lifecycle)
        val effect = assertIs<ProgramRuntimeActorEffect.CompleteAddons>(service.continuations.single().single())
        assertEquals(listOf(VmHostRequestIdentity(1, 1)), effect.completions.map { it.identity })
        carrier.closeAsync().join()
    }

    private fun computer(
        service: Service,
        addon: ProgramAddonHost,
    ): ActorProgramComputer {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 2), 1)
        val lease =
            ProgramRuntimeActorLease(endpoint, CompletableFuture.completedFuture(7L), {}, { CompletableFuture.completedFuture(true) })
        return ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed }, addon = addon)
    }

    private fun request(
        id: Long,
        programId: Long = 0,
    ) = ProgramAddonAction.Request(
        ProgramAddonRequest(VmHostRequestIdentity(1, id), CapabilityIdentity("test", "device", 1, 0), 0, emptyList(), programId),
    )

    private fun reply() = ProgramRuntimeActorReply(ProgramRuntimeRequestId(1), ProgramRuntimeState.Running, ProgramRuntimeActorValue.None)

    private open class TestAddon : ProgramAddonHost {
        override val capabilitySchemas: List<HostCapabilitySchema> = emptyList()

        override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch = error("unexpected dispatch")

        override fun reset() = Unit
    }

    private class Service : ActorRuntimeService {
        override val redstoneInputCapacity = 1
        var completion: ProgramRuntimeTurnCompletion? = null
        val continuations = mutableListOf<List<ProgramRuntimeActorEffect>>()

        override fun request(
            endpoint: VmActorEndpoint,
            command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
        ): CompletableFuture<ProgramRuntimeActorReply> {
            val id = ProgramRuntimeRequestId(1)
            command(id)
            return CompletableFuture.completedFuture(
                ProgramRuntimeActorReply(id, ProgramRuntimeState.Running, ProgramRuntimeActorValue.None),
            )
        }

        override fun submitTurn(
            endpoint: VmActorEndpoint,
            worldTick: Long,
            effects: List<ProgramRuntimeActorEffect>,
            completion: ProgramRuntimeTurnCompletion,
        ): VmActorSubmission {
            this.completion = completion
            return VmActorSubmission.ACCEPTED
        }

        override fun continueTurn(
            endpoint: VmActorEndpoint,
            worldTick: Long,
            effects: List<ProgramRuntimeActorEffect>,
            completion: ProgramRuntimeTurnCompletion,
        ): Boolean {
            continuations += effects
            this.completion = completion
            return true
        }

        override fun recordHostContinuationDelay(delayTicks: Long) = Unit

        override fun recordCoalescedRedstoneInput() = Unit
    }
}
