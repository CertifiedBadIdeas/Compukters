/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActorProgramComputerStreamingTest {
    @Test
    fun `hibernation retries remaining reply prefix without executing Guest`() {
        val service = Service(1)
        var captured: ProgramActorHibernation? = null
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 2), 1)
        val lease =
            ProgramRuntimeActorLease(endpoint) { request ->
                captured = request
                ProgramActorCloseReservation { CompletableFuture.completedFuture(7) }
            }
        val carrier =
            ActorProgramComputer(
                service,
                lease,
                { RedstoneCommitResult.Committed },
                addon =
                    object : TestAddon() {
                        override fun dispatch(request: ProgramAddonRequest) = ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                    },
            )
        carrier.turnOn().join()
        carrier.serverTick(1)
        service.queued = 0
        val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
        observer.addonAction(request(1))
        observer.addonAction(request(2))
        observer.complete(reply().copy(state = ProgramRuntimeState.WaitingForInput), null)
        assertNull(carrier.tryHibernateAsync(1))
        assertEquals(ProgramRuntimeState.WaitingForInput, carrier.state)
        assertNull(captured)
        service.queued = 0
        assertEquals(7L, checkNotNull(carrier.tryHibernateAsync(1)).join())
        assertTrue(checkNotNull(captured).effects.isEmpty())
        assertEquals(listOf(VmHostRequestIdentity(1, 1), VmHostRequestIdentity(1, 2)), service.acceptedReplies)
        assertEquals(1, service.acceptedTurns)
    }

    @Test
    fun `bounded reply prefixes resume a waiting carrier after grant refusal`() {
        for (capacity in listOf(1, 2)) {
            val service = Service(capacity)
            val carrier =
                computer(
                    service,
                    object : TestAddon() {
                        override fun dispatch(request: ProgramAddonRequest) = ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)
                    },
                )
            carrier.turnOn().join()
            carrier.serverTick(1)
            service.queued = 0 // Native worker consumed the initial grant.
            val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
            observer.addonAction(request(1))
            observer.addonAction(request(2))
            observer.complete(reply().copy(state = ProgramRuntimeState.WaitingForInput), null)
            assertEquals(capacity, service.acceptedReplies.size)
            assertEquals(1, service.acceptedTurns)
            for (tick in 2L..4L) {
                service.queued = 0 // Worker consumes the accepted prefix between ticks.
                carrier.serverTick(tick)
            }
            assertEquals(listOf(VmHostRequestIdentity(1, 1), VmHostRequestIdentity(1, 2)), service.acceptedReplies)
            assertTrue(service.acceptedTurns > 1)
            carrier.closeAsync().join()
        }
    }

    @Test
    fun `refused hibernation retains carrier state addon resources and acknowledged replies`() {
        val service = Service()
        var addonClosed = 0
        val addon =
            object : TestAddon() {
                override fun dispatch(request: ProgramAddonRequest) = ProgramAddonDispatch.Completed(HostResponse.UnitSuccess)

                override fun close() {
                    addonClosed++
                }
            }
        var busy = true
        var accepted: ProgramActorHibernation? = null
        var commits = 0
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(0, 1), 1)
        val lease =
            ProgramRuntimeActorLease(endpoint) { request ->
                if (busy) throw ProgramActorCloseDeferredException()
                accepted = request
                ProgramActorCloseReservation {
                    commits++
                    CompletableFuture.completedFuture(7)
                }
            }
        val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed }, addon = addon)
        carrier.turnOn().join()
        carrier.serverTick(4)
        val observer = assertIs<ProgramRuntimeTurnObserver>(service.completion)
        observer.addonAction(request(1))
        assertNull(carrier.tryHibernateAsync(4))
        val refused = carrier.hibernateAsync(4)
        assertTrue(refused.isCompletedExceptionally)
        assertEquals(ProgramRuntimeState.Running, carrier.state)
        assertEquals(0, addonClosed)
        assertEquals(0, commits)
        busy = false
        assertEquals(7L, carrier.hibernateAsync(4).join())
        val replies = assertIs<ProgramRuntimeActorEffect.CompleteAddons>(checkNotNull(accepted).effects.single())
        assertEquals(listOf(VmHostRequestIdentity(1, 1)), replies.completions.map { it.identity })
        observer.complete(reply(), null)
        assertTrue(service.continuations.isEmpty())
        assertEquals(ProgramRuntimeState.Closed, carrier.state)
        assertEquals(1, addonClosed)
        assertEquals(1, commits)
    }

    @Test
    fun `accepted close failing immediately is a terminal barrier rather than deferred admission`() {
        val service = Service()
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(0, 1), 1)
        var commits = 0
        val lease =
            ProgramRuntimeActorLease(endpoint) {
                ProgramActorCloseReservation {
                    commits++
                    CompletableFuture.failedFuture(ProgramActorCloseDeferredException())
                }
            }
        val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
        carrier.turnOn().join()
        val accepted = checkNotNull(carrier.tryHibernateAsync(4))
        assertTrue(accepted.isCompletedExceptionally)
        assertEquals(ProgramRuntimeState.Closed, carrier.state)
        assertTrue(checkNotNull(carrier.tryHibernateAsync(5)).isCompletedExceptionally)
        assertEquals(1, commits)
    }

    @Test
    fun `carrier fences callbacks before backend commits reentrant close`() {
        val service = Service()
        lateinit var carrier: ActorProgramComputer
        var commits = 0
        var addonClosed = 0
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(0, 1), 1)
        val lease =
            ProgramRuntimeActorLease(endpoint) {
                ProgramActorCloseReservation {
                    commits++
                    val nested = carrier.closeAsync()
                    assertFalse(nested.isDone)
                    checkNotNull(service.completion).complete(reply(), IllegalStateException("turn canceled"))
                    CompletableFuture.completedFuture(8)
                }
            }
        carrier =
            ActorProgramComputer(
                service,
                lease,
                { RedstoneCommitResult.Committed },
                addon =
                    object : TestAddon() {
                        override fun close() {
                            addonClosed++
                            error("cleanup failed")
                        }
                    },
            )
        carrier.turnOn().join()
        carrier.serverTick(4)
        assertEquals(8L, carrier.closeAsync().join())
        assertEquals(ProgramRuntimeState.Closed, carrier.state)
        assertEquals(1, commits)
        assertEquals(1, addonClosed)
    }

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

    private class Service(
        private val replyCapacity: Int? = null,
    ) : ActorRuntimeService {
        var queued = 0
        var acceptedTurns = 0
        val acceptedReplies = mutableListOf<VmHostRequestIdentity>()

        override fun submitAddonCompletions(
            endpoint: VmActorEndpoint,
            completions: List<ProgramAddonCompletion>,
        ): Int? {
            val capacity = replyCapacity ?: return null
            val accepted = minOf(completions.size, capacity - queued)
            acceptedReplies += completions.take(accepted).map { it.identity }
            queued += accepted
            return accepted
        }

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
            if (replyCapacity != null && queued >= replyCapacity) return VmActorSubmission.MAILBOX_FULL
            queued++
            acceptedTurns++
            this.completion = completion
            return VmActorSubmission.ACCEPTED
        }

        override fun continueTurn(
            endpoint: VmActorEndpoint,
            worldTick: Long,
            effects: List<ProgramRuntimeActorEffect>,
            completion: ProgramRuntimeTurnCompletion,
        ): Boolean {
            if (replyCapacity != null && queued >= replyCapacity) return false
            queued++
            acceptedTurns++
            continuations += effects
            this.completion = completion
            return true
        }

        override fun recordHostContinuationDelay(delayTicks: Long) = Unit

        override fun recordCoalescedRedstoneInput() = Unit
    }
}
