/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetInputResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeFleetActorRuntimeServiceTest {
    @Test
    fun `frame is not refilled and continuation reports only newly retired instructions`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val replies = mutableListOf<ProgramRuntimeActorReply>()
            val completion =
                ProgramRuntimeTurnCompletion { reply, error ->
                    check(error == null)
                    replies += checkNotNull(reply)
                }
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            val runtime: ActorRuntimeService = h.service
            assertEquals(VmActorSubmission.ACCEPTED, runtime.submitTurn(endpoint, 4, emptyList(), completion))
            assertFalse(h.service.grantFrame(endpoint, 5, 100))
            assertEquals(VmActorSubmission.PERMIT_PENDING, runtime.submitTurn(endpoint, 4, emptyList(), completion))
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 6, false)
            h.service.pump(10, 10)
            assertFalse(h.service.grantFrame(endpoint, 4, 100))
            assertTrue(runtime.continueTurn(endpoint, 4, emptyList(), completion))
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 10, false)
            h.service.pump(10, 10)
            assertEquals(listOf(20, 14), h.grants.map { it.second })
            assertEquals(listOf(6L, 4L), replies.map { it.retiredInstructions })
            // A duplicate report after settlement completes nothing and creates no new grant.
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 10, false)
            h.service.pump(10, 10)
            assertEquals(2, replies.size)
            assertTrue(h.service.grantFrame(endpoint, 5, 3))
            assertEquals(VmActorSubmission.ACCEPTED, runtime.submitTurn(endpoint, 5, emptyList(), completion))
        }

    @Test
    fun `cancellation retains query admission until its native response arrives`() =
        Harness(maximumRequests = 2).use { h ->
            val endpoint = h.register(1)

            fun query() = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            val first = query()
            val second = query()
            assertTrue(first.cancel(false))
            assertTrue(second.cancel(false))
            assertTrue(query().isCompletedExceptionally)
            h.service.pump(0, 10)
            val sent = h.queries.toList()
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, sent[0], 7)
            h.service.pump(1, 0)
            assertFalse(query().isCompletedExceptionally)
            // Removing another response frees its slot too; canceled futures stay canceled.
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, sent[1], 8)
            h.service.pump(1, 0)
            assertTrue(first.isCancelled && second.isCancelled)
        }

    @Test
    fun `stop cancels pending work and stale events cannot reach a replacement endpoint`() =
        Harness().use { h ->
            val original = h.register(1)
            val query = h.service.request(original, ProgramRuntimeActorCommand::FileSystemGeneration)
            val reply = CompletableFuture<ProgramRuntimeActorReply>()
            assertTrue(h.service.grantFrame(original, 1, 20))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(original, 1, emptyList()) { value, failure ->
                    if (failure == null) reply.complete(value) else reply.completeExceptionally(failure)
                },
            )
            val stopped = checkNotNull(h.service.stop(original))
            assertEquals(VmActorSubmission.CLOSED, h.service.submitTurn(original, 1, emptyList()) { _, _ -> error("not accepted") })
            h.service.pump(0, 10)
            h.service.pump(10, 10)
            assertEquals(7L, stopped.join())
            assertTrue(query.isCompletedExceptionally && reply.isCompletedExceptionally)
            h.observed.clear()
            val replacement = h.register(2, original.computerId, 2)
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.Halted(null))
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 20, false)
            h.events += ExperimentalFleetEvent.Error(1, "late failure from a stopped machine")
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, h.queries.first(), 999)
            h.service.pump(10, 10)
            assertTrue(h.observed.isEmpty())
            assertTrue(h.service.grantFrame(replacement, 2, 10))
            assertFalse(h.service.grantFrame(original, 2, 10))
        }

    @Test
    fun `expired grants reject without callbacks and a callback failure does not block another actor`() =
        Harness().use { h ->
            val a = h.register(1)
            val b = h.register(2)
            assertTrue(h.service.grantFrame(a, 1, 10, 100))
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(a, 1, emptyList()) { _, _ -> error("expired") })
            assertTrue(h.service.grantFrame(a, 2, 10))
            assertTrue(h.service.grantFrame(b, 2, 10))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(a, 2, emptyList()) { _, _ -> error("observer failed") })
            var completed = false
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(b, 2, emptyList()) { _, error ->
                    check(error == null)
                    completed =
                        true
                },
            )
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 2, 3, false)
            h.events += ExperimentalFleetEvent.Quiescent(2, 2, 4, false)
            assertEquals(2, h.service.pump(10, 10))
            assertTrue(completed)
            assertFailsWith<IllegalStateException> { h.service.closeAsync() }
            Unit
        }

    @Test
    fun `stop drains a full outbox without owner pumping and cancellation cannot cancel its barrier`() {
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            val query = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(endpoint, 1, emptyList()) { _, _ -> error("rejected") })
            val first = checkNotNull(h.service.stop(endpoint))
            first.cancel(false)
            val second = checkNotNull(h.service.stop(endpoint))
            assertEquals(7L, second.get(5, TimeUnit.SECONDS))
            assertTrue(first.isCancelled && query.isCompletedExceptionally)
            assertEquals(1, h.queries.size)
            assertEquals(listOf(1L), h.closedMachines)
        }
    }

    @Test
    fun `failed host cleanup fails its barrier and releases pending requests without blocking another stop`() =
        Harness(closeFailureFor = 1).use { h ->
            val a = h.register(1)
            val b = h.register(2)
            val query = h.service.request(a, ProgramRuntimeActorCommand::FileSystemGeneration)
            val failed = checkNotNull(h.service.stop(a))
            val succeeded = checkNotNull(h.service.stop(b))
            h.service.pump(0, 16)
            h.service.pump(16, 16)
            assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
            assertTrue(query.isCompletedExceptionally)
            assertEquals(7L, succeeded.get(5, TimeUnit.SECONDS))
        }

    @Test
    fun `ordered input wakes host observations without calling the transferred session`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val states = mutableListOf<ProgramRuntimeState>()
            val completion = ProgramRuntimeTurnCompletion { reply, failure -> if (failure == null) states += checkNotNull(reply).state }
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 1, emptyList(), completion))
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.WaitingForTerminalEvent)
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 0, false)
            h.service.pump(8, 8)
            assertTrue(h.service.grantFrame(endpoint, 2, 10))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(endpoint, 2, listOf(ProgramRuntimeActorEffect.RedstoneInput(0)), completion),
            )
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Quiescent(1, 2, 0, false)
            h.service.pump(8, 8)
            assertEquals(listOf(ProgramRuntimeState.WaitingForInput, ProgramRuntimeState.Running), states)
        }

    @Test
    fun `cancellation callback failure still closes host and fails its barrier`() =
        Harness(cancellationFailureFor = 1).use { h ->
            val endpoint = h.register(1)
            val stopped = checkNotNull(h.service.stop(endpoint))
            h.service.pump(0, 8)
            h.service.pump(8, 8)
            assertFailsWith<ExecutionException> { stopped.get(5, TimeUnit.SECONDS) }
            assertEquals(listOf(1L), h.closedMachines)
        }

    @Test
    fun `refused addon completion keeps its grant and retries without duplicating an accepted native reply`() {
        Harness(byteLimits = NativeFleetOutboxLimits(192, 192)).use { h ->
            val endpoint = h.register(1)
            val identity = VmHostRequestIdentity(1, 1)
            val effects =
                listOf(ProgramRuntimeActorEffect.CompleteAddons(listOf(ProgramAddonCompletion(identity, HostResponse.UnitSuccess))))
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            val results = mutableListOf<ProgramRuntimeActorReply>()
            val completion = ProgramRuntimeTurnCompletion { reply, failure -> if (failure == null) results += checkNotNull(reply) }
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(endpoint, 4, effects, completion))
            assertTrue(h.replies.isEmpty() && h.grants.isEmpty())
            h.service.pump(0, 8)
            h.busyReplies = true
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 4, effects, completion))
            h.service.pump(0, 8)
            assertTrue(h.replies.isEmpty() && h.grants.isEmpty())
            h.busyReplies = false
            h.service.pump(0, 1) // Accept the reply, but leave its grant queued.
            assertEquals(listOf(identity), h.replies)
            h.service.pump(0, 8)
            assertEquals(listOf(identity), h.replies)
            assertEquals(listOf(1L to 20), h.grants)
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 6, false)
            h.service.pump(8, 0)
            assertEquals(6L, results.single().retiredInstructions)
            assertTrue(h.service.continueTurn(endpoint, 4, emptyList(), completion))
            h.service.pump(0, 8)
            assertEquals(listOf(1L to 20, 1L to 14), h.grants)
        }
    }

    @Test
    fun `busy close preserves acknowledged replies without blocking another close or sending unsent execution credit`() {
        Harness().use { h ->
            val endpoint = h.register(1)
            val identity = VmHostRequestIdentity(1, 1)
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            val turn = CompletableFuture<ProgramRuntimeActorReply>()
            val effects =
                listOf(ProgramRuntimeActorEffect.CompleteAddons(listOf(ProgramAddonCompletion(identity, HostResponse.UnitSuccess))))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(endpoint, 1, effects) { reply, failure ->
                    if (failure == null) turn.complete(reply) else turn.completeExceptionally(failure)
                },
            )
            val other = h.register(2)
            h.busyReplies = true
            val stopped = checkNotNull(h.service.stop(endpoint))
            h.service.pump(8, 8)
            assertTrue(!stopped.isDone && turn.isCompletedExceptionally && h.replies.isEmpty())
            val otherStopped = checkNotNull(h.service.stop(other))
            assertEquals(7L, otherStopped.get(5, TimeUnit.SECONDS))
            assertTrue(!stopped.isDone)
            h.busyReplies = false
            h.service.pump(8, 8)
            h.service.pump(8, 8)
            assertEquals(7L, stopped.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(identity), h.replies)
            assertTrue(turn.isCompletedExceptionally)
            assertTrue(h.grants.isEmpty())
        }
    }

    @Test
    fun `flattened completion count and invalid payload cannot consume a frame on refusal`() {
        Harness(maximumRequests = 2).use { h ->
            val endpoint = h.register(1)
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            val tooMany =
                listOf(
                    ProgramRuntimeActorEffect.CompleteAddons(
                        listOf(
                            ProgramAddonCompletion(VmHostRequestIdentity(1, 1), HostResponse.UnitSuccess),
                            ProgramAddonCompletion(VmHostRequestIdentity(1, 2), HostResponse.UnitSuccess),
                        ),
                    ),
                )
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(endpoint, 1, tooMany) { _, _ -> error("rejected") })
            val invalid =
                listOf(
                    ProgramRuntimeActorEffect.CompleteAddons(
                        listOf(
                            ProgramAddonCompletion(VmHostRequestIdentity(1, 1), HostResponse.StringSuccess("x".repeat(4097))),
                        ),
                    ),
                )
            assertFailsWith<IllegalArgumentException> { h.service.submitTurn(endpoint, 1, invalid) { _, _ -> error("invalid") } }
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 1, emptyList()) { _, _ -> })
            h.service.pump(0, 8)
            assertTrue(h.replies.isEmpty())
            assertEquals(listOf(1L to 10), h.grants)
        }
    }

    @Test
    fun `automatic native execution is rejected before allocating a dispatcher`() {
        val uncoordinated =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, _ ->
                check(method.name == "getCoordinatedTurns") { "uncoordinated fleet must not start a dispatcher" }
                false
            } as ExperimentalFleet
        assertFailsWith<IllegalArgumentException> {
            NativeFleetActorRuntimeService(uncoordinated, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {})
        }
    }

    @Test
    fun `invalid payloads are rejected before admission without consuming query capacity`() {
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            val path = VmVirtualPath.of("/home")
            val invalid: List<(ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand> =
                listOf(
                    { ProgramRuntimeActorCommand.SendTerminalText(it, "a".repeat(4097)) },
                    { ProgramRuntimeActorCommand.SendTerminalText(it, "\ud800") },
                    { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, CharArray(4097)) },
                    { ProgramRuntimeActorCommand.FileList(it, path, null, 257) },
                    { ProgramRuntimeActorCommand.FileList(it, path, "", 1) },
                    { ProgramRuntimeActorCommand.FileList(it, path, "é".repeat(2049), 1) },
                    { ProgramRuntimeActorCommand.FileRead(it, path, 0, 32769, 0) },
                )
            invalid.forEach { command ->
                val result = h.service.request(endpoint, command).join()
                assertIs<ProgramRuntimeActorFailure.InvalidRequest>(assertIs<ProgramRuntimeActorValue.Rejected>(result.value).failure)
            }
            val accepted = h.service.request(endpoint) { ProgramRuntimeActorCommand.SendTerminalText(it, "\ud83d\ude00".repeat(4096)) }
            h.service.pump(0, 8)
            assertEquals(1, h.queries.size)
            h.events += ExperimentalFleetEvent.InputResult(1, h.queries.single(), ExperimentalFleetInputResult.Accepted)
            h.service.pump(8, 8)
            assertEquals(ProgramRuntimeActorValue.Accepted(true), accepted.join().value)
        }
    }

    @Test
    fun `only acknowledged terminal input wakes host and cancelled input still reconciles state`() {
        Harness().use { h ->
            val endpoint = h.register(1)
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 1, emptyList()) { _, _ -> })
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.WaitingForTerminalEvent)
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 0, false)
            h.service.pump(8, 8)
            val refused = h.service.request(endpoint) { ProgramRuntimeActorCommand.SendTerminalText(it, "hello") }
            val accepted =
                h.service.request(endpoint) {
                    ProgramRuntimeActorCommand.SendTerminalKey(it, TerminalKey.ENTER, TerminalKeyAction.PRESS, emptySet())
                }
            accepted.cancel(false)
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.InputResult(1, h.queries[0], ExperimentalFleetInputResult.TerminalRejected)
            h.service.pump(1, 0)
            assertEquals(ProgramRuntimeState.WaitingForInput, refused.join().state)
            assertEquals(ProgramRuntimeActorValue.Accepted(false), refused.join().value)
            h.events += ExperimentalFleetEvent.InputResult(1, h.queries[1], ExperimentalFleetInputResult.Accepted)
            h.service.pump(1, 0)
            val state = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, h.queries.last(), 7)
            h.service.pump(1, 0)
            assertEquals(ProgramRuntimeState.Running, state.join().state)
            assertTrue(accepted.isCancelled)
        }
    }

    @Test
    fun `typed reply mismatch releases admission and late duplicates do not update host generation`() {
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            val terminal = h.service.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
            h.service.pump(0, 8)
            val old = h.queries.single()
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, old, 999)
            h.service.pump(1, 0)
            assertTrue(terminal.isCompletedExceptionally)
            val generation = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, h.queries.last(), 7)
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, old, 1000)
            h.service.pump(8, 0)
            assertEquals(7L, generation.join().fileSystemGeneration)
            val next = h.service.request(endpoint) { ProgramRuntimeActorCommand.TerminalChangesSince(it, 0) }
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Terminal(1, h.queries.last(), TerminalUpdate.Unchanged(0))
            h.service.pump(1, 0)
            assertEquals(7L, next.join().fileSystemGeneration)
        }
    }

    @Test
    fun `file failure is typed and file chunk owns its returned bytes`() {
        Harness().use { h ->
            val endpoint = h.register(1)
            val path = VmVirtualPath.of("/home/file")
            val absent = h.service.request(endpoint) { ProgramRuntimeActorCommand.FileStat(it, path) }
            val read = h.service.request(endpoint) { ProgramRuntimeActorCommand.FileRead(it, path, 0, 32, 7) }
            h.service.pump(0, 8)
            val bytes = byteArrayOf(1, 2, 3)
            h.events += ExperimentalFleetEvent.FileFailure(1, h.queries[0], VmFileSystemReadFailure.NOT_FOUND)
            h.events += ExperimentalFleetEvent.FileRead(1, h.queries[1], VmFileChunk(7, 3, true, bytes))
            h.service.pump(8, 0)
            assertEquals(
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.NOT_FOUND)),
                absent.join().value,
            )
            bytes.fill(9)
            assertContentEquals(byteArrayOf(1, 2, 3), assertIs<ProgramRuntimeActorValue.FileChunkValue>(read.join().value).chunk!!.bytes)
        }
    }

    @Test
    fun `addon actions stream before settlement with native scope ownership and no raw duplicate`() {
        Harness().use { h ->
            val capability = CapabilityIdentity("test", "device", 1, 0)
            val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
            val endpoint = h.register(1, schemas = listOf(schema))
            val actions = mutableListOf<ProgramAddonAction>()
            val replies = mutableListOf<ProgramRuntimeActorReply>()
            val observer =
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) {
                        actions += action
                    }

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) {
                        check(failure == null)
                        replies += checkNotNull(reply)
                    }
                }
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 4, emptyList(), observer))
            val request = VmHostRequest(1, capability, 0, emptyList())
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.ProcessEntered(1))
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.HostRequestBatch(listOf(request)))
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.ProcessReplaced(1, 2))
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.HostRequestBatch(listOf(request.copy(id = 2))))
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.ProcessExited(2))
            h.service.pump(10, 10)
            assertTrue(replies.isEmpty())
            assertFalse(h.service.grantFrame(endpoint, 5, 20))
            assertFalse(h.service.continueTurn(endpoint, 4, emptyList(), observer))
            assertTrue(h.observed.isEmpty())
            assertEquals(listOf(1L, 2L), actions.filterIsInstance<ProgramAddonAction.Request>().map { it.request.programId })
            assertEquals(
                listOf(
                    ProgramAddonAction.Started(1),
                    ProgramAddonAction.Stopped(1),
                    ProgramAddonAction.Started(2),
                    ProgramAddonAction.Stopped(2),
                ),
                actions.filter {
                    it !is ProgramAddonAction.Request
                },
            )
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 6, false)
            h.service.pump(10, 10)
            assertEquals(listOf(6L), replies.map { it.retiredInstructions })
            assertTrue(h.service.continueTurn(endpoint, 4, emptyList(), observer))
            h.service.pump(0, 10)
            assertEquals(listOf(20, 14), h.grants.map { it.second })
        }
    }

    @Test
    fun `reentrant stop suppresses the rest of an addon batch`() {
        Harness().use { h ->
            val capability = CapabilityIdentity("test", "device", 1, 0)
            val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
            val endpoint = h.register(1, schemas = listOf(schema))
            var deliveries = 0
            var canceled = false
            val observer =
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) {
                        deliveries++
                        checkNotNull(h.service.stop(endpoint))
                        assertTrue(h.closedMachines.isEmpty())
                    }

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) {
                        canceled = failure is java.util.concurrent.CancellationException
                    }
                }
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            h.service.submitTurn(endpoint, 4, emptyList(), observer)
            h.events +=
                ExperimentalFleetEvent.Outcome(
                    1,
                    4,
                    VmOutcome.HostRequestBatch(
                        listOf(VmHostRequest(1, capability, 0, emptyList()), VmHostRequest(2, capability, 0, emptyList())),
                    ),
                )
            h.service.pump(10, 10)
            h.service.pump(10, 10)
            assertEquals(1, deliveries)
            assertTrue(canceled)
        }
    }

    @Test
    fun `observer failure settles reservation and does not block another actor`() {
        Harness().use { h ->
            val capability = CapabilityIdentity("test", "device", 1, 0)
            val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
            val a = h.register(1, schemas = listOf(schema))
            val b = h.register(2)
            var deliveries = 0
            var failed = false
            var otherCompleted = false
            val observer =
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) {
                        deliveries++
                        error("handler failed")
                    }

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) {
                        failed = failure is IllegalStateException
                    }
                }
            assertTrue(h.service.grantFrame(a, 4, 20))
            assertTrue(h.service.grantFrame(b, 4, 20))
            h.service.submitTurn(a, 4, emptyList(), observer)
            h.service.submitTurn(b, 4, emptyList()) { _, failure ->
                check(failure == null)
                otherCompleted = true
            }
            h.events +=
                ExperimentalFleetEvent.Outcome(
                    1,
                    4,
                    VmOutcome.HostRequestBatch(
                        listOf(VmHostRequest(1, capability, 0, emptyList()), VmHostRequest(2, capability, 0, emptyList())),
                    ),
                )
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 6, false)
            h.events += ExperimentalFleetEvent.Quiescent(2, 4, 2, false)
            h.service.pump(10, 10)
            assertEquals(1, deliveries)
            assertTrue(failed && otherCompleted)
            assertTrue(h.service.grantFrame(a, 5, 20))
        }
    }

    @Test
    fun `mixed batches forward only unmanaged requests and final-only callers retain the raw hook`() {
        for (streaming in listOf(false, true)) {
            Harness().use { h ->
                val capability = CapabilityIdentity("test", "device", 1, 0)
                val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
                val endpoint = h.register(1, schemas = listOf(schema))
                val requests =
                    listOf(
                        VmHostRequest(1, CapabilityIdentity("compukter", "timer", 1, 0), 0, emptyList()),
                        VmHostRequest(2, capability, 0, emptyList()),
                        VmHostRequest(3, capability.copy(abiMinor = 1), 0, emptyList()),
                    )
                var delivered = 0
                val completion: ProgramRuntimeTurnCompletion =
                    if (streaming) {
                        object : ProgramRuntimeTurnObserver {
                            override fun addonAction(action: ProgramAddonAction) {
                                delivered++
                                assertEquals(requests[1].identity, assertIs<ProgramAddonAction.Request>(action).request.identity)
                            }

                            override fun complete(
                                reply: ProgramRuntimeActorReply?,
                                failure: Throwable?,
                            ) = Unit
                        }
                    } else {
                        ProgramRuntimeTurnCompletion { _, _ -> }
                    }
                assertTrue(h.service.grantFrame(endpoint, 4, 20))
                h.service.submitTurn(endpoint, 4, emptyList(), completion)
                h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.HostRequestBatch(requests))
                h.service.pump(10, 10)
                assertEquals(if (streaming) 1 else 0, delivered)
                val forwarded =
                    assertIs<VmOutcome.HostRequestBatch>(assertIs<ExperimentalFleetEvent.Outcome>(h.observedEvents.single()).outcome)
                assertEquals(if (streaming) listOf(requests[0], requests[2]) else requests, forwarded.requests)
            }
        }
    }

    @Test
    fun `stop from replacement cleanup suppresses the following program start`() {
        Harness().use { h ->
            val endpoint = h.register(1)
            val actions = mutableListOf<ProgramAddonAction>()
            val observer =
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) {
                        actions += action
                        checkNotNull(h.service.stop(endpoint))
                        assertTrue(h.closedMachines.isEmpty())
                    }

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) = Unit
                }
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            h.service.submitTurn(endpoint, 4, emptyList(), observer)
            h.events += ExperimentalFleetEvent.Outcome(1, 4, VmOutcome.ProcessReplaced(0, 1))
            h.service.pump(10, 10)
            assertEquals(listOf<ProgramAddonAction>(ProgramAddonAction.Stopped(0)), actions)
        }
    }

    @Test
    fun `reentrant service shutdown includes the reserved close and needs no result pumping`() {
        var shutdown: CompletableFuture<Unit>? = null
        Harness(onClosing = { service -> shutdown = service.closeAsync() }).use { h ->
            val endpoint = h.register(1)
            val stopped = checkNotNull(h.service.stop(endpoint))
            assertEquals(7L, stopped.get(5, TimeUnit.SECONDS))
            checkNotNull(shutdown).get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1L), h.closedMachines)
        }
    }

    private class Harness(
        maximumRequests: Int = 16,
        private val closeFailureFor: Long? = null,
        private val cancellationFailureFor: Long? = null,
        byteLimits: NativeFleetOutboxLimits = NativeFleetOutboxLimits(),
        private val onClosing: (NativeFleetActorRuntimeService) -> Unit = {},
    ) : AutoCloseable {
        val events = ConcurrentLinkedQueue<ExperimentalFleetEvent>()
        val grants = CopyOnWriteArrayList<Pair<Long, Int>>()
        val queries = CopyOnWriteArrayList<Long>()
        val observed = mutableListOf<VmActorEndpoint>()
        val observedEvents = mutableListOf<ExperimentalFleetEvent>()
        val closedMachines = CopyOnWriteArrayList<Long>()
        val replies = CopyOnWriteArrayList<VmHostRequestIdentity>()

        @Volatile var busyReplies = false
        private val endpoints = mutableListOf<VmActorEndpoint>()
        private val stops = ConcurrentHashMap<Long, ExperimentalFleetEvent>()
        private val owner = Thread.currentThread()
        private val fleet =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                when (method.name.substringBefore('-')) {
                    "getCoordinatedTurns" -> {
                        true
                    }

                    "redstoneInput" -> {
                        null
                    }

                    "reply" -> {
                        if (busyReplies) {
                            throw ru.lazyhat.compukters.lang.runtime.vm
                                .VmFleetBusyException("full")
                        }
                        replies += args!![1] as VmHostRequestIdentity
                        null
                    }

                    "eventGeneration" -> {
                        0L
                    }

                    "waitEvents" -> {
                        TimeUnit.MILLISECONDS.sleep(10)
                        false
                    }

                    "poll" -> {
                        events.poll()
                    }

                    "grant" -> {
                        grants += (args!![0] as Long) to (args[2] as Int)
                        null
                    }

                    "filesystemGeneration" -> {
                        queries += args!![1] as Long
                        null
                    }

                    "terminalUpdate", "fileStat", "fileList", "fileRead" -> {
                        queries += args!![1] as Long
                        null
                    }

                    "terminalText", "canonicalLine" -> {
                        queries += args!![2] as Long
                        null
                    }

                    "terminalKey" -> {
                        queries += args!![4] as Long
                        null
                    }

                    "stop" -> {
                        stops[args!![0] as Long] = ExperimentalFleetEvent.Stopped(args[0] as Long, emptyList(), 7)
                        null
                    }

                    "pollLifecycle" -> {
                        stops.remove(args!![0] as Long)
                    }

                    else -> {
                        error("unexpected fleet call ${method.name}")
                    }
                }
            } as ExperimentalFleet
        val service =
            NativeFleetActorRuntimeService(fleet, 4, maximumRequests, maximumRequests, {
                check(
                    Thread.currentThread() === owner,
                )
            }, {}, { endpoint, event ->
                observed += endpoint
                observedEvents += event
            }, { 100 }, byteLimits, { endpoint ->
                handleClosing()
                check(endpoint.computerId != cancellationFailureFor?.let { ComputerId.fromLongs(0, it) }) { "cancellation failed" }
            })

        private fun handleClosing() {
            check(Thread.currentThread() === owner)
            onClosing(service)
        }

        fun register(
            machine: Long,
            computerId: ComputerId = ComputerId.fromLongs(0, machine),
            epoch: Long = 1,
            schemas: List<HostCapabilitySchema> = emptyList(),
        ): VmActorEndpoint {
            val session =
                Proxy.newProxyInstance(ProgramVmSession::class.java.classLoader, arrayOf(ProgramVmSession::class.java)) { _, method, _ ->
                    when (method.name) {
                        "transferToFleet" -> {
                            machine
                        }

                        "filesystemGeneration" -> {
                            7L
                        }

                        "confirmRedstoneOutput", "submitRedstoneInput", "acknowledgeFleetStop" -> {
                            null
                        }

                        "close" -> {
                            closedMachines += machine
                            check(machine != closeFailureFor) { "host close failed" }
                            null
                        }

                        else -> {
                            error("unexpected session call ${method.name}")
                        }
                    }
                } as ProgramVmSession
            val factory =
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray) = session
                }
            val host = ProgramRuntimeHost(factory, addonCapabilitySchemas = schemas)
            host.start(byteArrayOf())
            host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(computerId, epoch)
            assertTrue(service.register(endpoint, machine, host))
            endpoints += endpoint
            return endpoint
        }

        override fun close() {
            endpoints.forEach { service.stop(it) }
            service.close()
        }
    }
}
