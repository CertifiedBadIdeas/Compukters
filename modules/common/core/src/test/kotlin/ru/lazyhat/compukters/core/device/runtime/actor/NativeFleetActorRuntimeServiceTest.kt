/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationAddress
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationOutcome
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationRequest
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompiler
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilerCompletion
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult
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
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentFailure
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentResult
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDetachedState
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetInputResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
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
    fun `scope cancellation rejects late compiler results after the computer restarts`() =
        Harness().use { h ->
            val compiler = FakeCompiler()
            val router = CompilerCompletionRouter(compiler)
            val id = ComputerId.fromLongs(10, 11)
            val first = h.register(1, computerId = id, compilerRouter = router)
            val request = VmCompilationRequest(1, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1))))
            h.events += ExperimentalFleetEvent.Compilation(1, 0, request)
            h.service.pump(1, 0)
            val oldAddress = compiler.requests.single().address
            h.events += ExperimentalFleetEvent.ScopeStopped(1, 0, emptyList())
            h.service.pump(1, 0)
            assertEquals(ProgramRuntimeState.Running, h.hosts.getValue(1).state)
            checkNotNull(h.service.stop(first)).get(5, TimeUnit.SECONDS)
            h.register(2, computerId = id, epoch = 2, compilerRouter = router)
            h.events += ExperimentalFleetEvent.Compilation(2, 0, request)
            h.service.pump(1, 0)
            val newAddress = compiler.requests.last().address
            assertTrue(oldAddress.vmEpoch != newAddress.vmEpoch)
            compiler.completions += ComputerCompilerCompletion(oldAddress, ComputerCompilationOutcome.Success(byteArrayOf(1)))
            compiler.completions += ComputerCompilerCompletion(newAddress, ComputerCompilationOutcome.Failure("new epoch failure"))
            repeat(3) { h.service.pump(0, 1) }
            assertEquals(listOf(Triple(2L, 1L, ComputerCompilationOutcome.Failure("new epoch failure"))), h.compilationResults.toList())
        }

    @Test
    fun `compiler result survives native backpressure and does not grant execution`() =
        Harness().use { h ->
            val compiler = FakeCompiler()
            val endpoint = h.register(1, compilerRouter = CompilerCompletionRouter(compiler))
            val request = VmCompilationRequest(1, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1))))
            h.events += ExperimentalFleetEvent.Compilation(1, 0, request)
            h.events += ExperimentalFleetEvent.Compilation(1, 0, request)
            h.service.pump(2, 0)
            assertEquals(1, compiler.requests.size)
            val address = compiler.requests.single().address
            compiler.completions += ComputerCompilerCompletion(address, ComputerCompilationOutcome.Success(byteArrayOf(1, 2, 3)))
            h.busyCompilation = true
            repeat(3) { h.service.pump(0, 1) }
            assertTrue(h.compilationResults.isEmpty())
            assertEquals(ProgramRuntimeState.WaitingForCompiler, h.hosts.getValue(1).state)
            h.busyCompilation = false
            h.service.pump(0, 1)
            assertEquals(listOf(Triple(1L, 1L, ComputerCompilationOutcome.Success(byteArrayOf(1, 2, 3)))), h.compilationResults.toList())
            assertEquals(ProgramRuntimeState.Running, h.hosts.getValue(1).state)
            assertTrue(h.grants.isEmpty())
            h.events += ExperimentalFleetEvent.Compilation(1, 0, request)
            h.service.pump(1, 1)
            assertEquals(1, compiler.requests.size)
            assertEquals(ProgramRuntimeState.Running, h.hosts.getValue(1).state)
            assertFalse(h.service.request(endpoint) { ProgramRuntimeActorCommand.FileSystemGeneration(it) }.isDone)
        }

    @Test
    fun `compiler cancellation fences an already queued result before off-thread close`() =
        Harness().use { h ->
            val compiler = FakeCompiler()
            val endpoint = h.register(1, compilerRouter = CompilerCompletionRouter(compiler))
            h.events +=
                ExperimentalFleetEvent.Compilation(
                    1,
                    0,
                    VmCompilationRequest(1, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1)))),
                )
            h.service.pump(1, 0)
            val address = compiler.requests.single().address
            compiler.completions += ComputerCompilerCompletion(address, ComputerCompilationOutcome.Success(byteArrayOf(3)))
            h.busyCompilation = true
            h.service.pump(0, 1)
            checkNotNull(h.service.stop(endpoint)).get(5, TimeUnit.SECONDS)
            assertEquals(listOf(address), compiler.cancellations)
            assertTrue(h.compilationResults.isEmpty())
        }

    @Test
    fun `compiler failures and oversized results make progress even in scalar-only queues`() {
        for (compiler in listOf(
            null,
            FakeCompiler(CompilerSubmissionResult.BUSY),
            FakeCompiler(CompilerSubmissionResult.CLOSED),
            FakeCompiler(),
        )) {
            Harness(byteLimits = NativeFleetOutboxLimits(64, 64)).use { h ->
                h.register(1, compilerRouter = compiler?.let { CompilerCompletionRouter(it) })
                h.events +=
                    ExperimentalFleetEvent.Compilation(
                        1,
                        0,
                        VmCompilationRequest(1, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1)))),
                    )
                h.service.pump(1, 0)
                if (compiler?.submission == CompilerSubmissionResult.ACCEPTED) {
                    compiler.completions +=
                        ComputerCompilerCompletion(compiler.requests.single().address, ComputerCompilationOutcome.Success(ByteArray(1024)))
                }
                h.service.pump(0, 1)
                assertEquals(ComputerCompilationOutcome.Failure(""), h.compilationResults.single().third)
                assertEquals(ProgramRuntimeState.Running, h.hosts.getValue(1).state)
            }
        }
    }

    private class FakeCompiler(
        val submission: CompilerSubmissionResult = CompilerSubmissionResult.ACCEPTED,
    ) : ComputerCompiler {
        val requests = mutableListOf<ComputerCompilationRequest>()
        val completions = ArrayDeque<ComputerCompilerCompletion>()
        val cancellations = mutableListOf<ComputerCompilationAddress>()

        override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
            requests += request
            return submission
        }

        override fun drain(maximum: Int): List<ComputerCompilerCompletion> =
            List(minOf(maximum, completions.size)) { completions.removeFirst() }

        override fun cancel(address: ComputerCompilationAddress): Boolean {
            cancellations += address
            return true
        }
    }

    @Test
    fun `an admitted install owns its token until native disposal is acknowledged`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val token = ProgramDeploymentToken(74)
            val first =
                h.service.request(
                    endpoint,
                ) { ProgramRuntimeActorCommand.Deploy(it, "/home/test", VmExecutableRevision.Absent, token) }
            val duplicate =
                h.service.request(
                    endpoint,
                ) { ProgramRuntimeActorCommand.Deploy(it, "/home/other", VmExecutableRevision.Absent, token) }
            assertTrue(duplicate.isDone)
            assertIs<ProgramRuntimeActorFailure.InvalidRequest>(assertIs<ProgramRuntimeActorValue.Rejected>(duplicate.join().value).failure)
            val discarded = h.service.request(endpoint) { ProgramRuntimeActorCommand.DiscardDeployment(it, token) }
            assertEquals(ProgramRuntimeActorValue.Accepted(false), discarded.join().value)
            assertFalse(first.isDone)
            h.service.pump(0, 8)
            assertEquals(1, h.queries.size)
        }

    @Test
    fun `oversized deployment rejects before queue admission and cannot strand a request`() =
        Harness(maximumRequests = 1, byteLimits = NativeFleetOutboxLimits(128, 128)).use { h ->
            val endpoint = h.register(1)
            val result = h.service.request(endpoint) { ProgramRuntimeActorCommand.PrepareDeployment(it, ByteArray(65)) }.join()
            assertIs<ProgramRuntimeActorFailure.InvalidRequest>(assertIs<ProgramRuntimeActorValue.Rejected>(result.value).failure)
            assertTrue(h.queries.isEmpty())
            assertFalse(h.service.request(endpoint) { ProgramRuntimeActorCommand.FileSystemGeneration(it) }.isDone)
        }

    @Test
    fun `canceled preparation retains admission until candidate is discarded`() =
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            val prepared = h.service.request(endpoint) { ProgramRuntimeActorCommand.PrepareDeployment(it, byteArrayOf(1, 2)) }
            h.service.pump(0, 1)
            val request = h.queries.single()
            prepared.cancel(false)
            h.events += ExperimentalFleetEvent.Deployment(1, request, ExperimentalFleetDeploymentResult.Prepared(71))
            h.service.pump(1, 0)
            assertTrue(h.service.request(endpoint) { ProgramRuntimeActorCommand.FileSystemGeneration(it) }.isCompletedExceptionally)
            h.events += ExperimentalFleetEvent.Deployment(1, request, ExperimentalFleetDeploymentResult.Prepared(71))
            h.service.pump(1, 1)
            assertEquals(listOf(71L), h.discardedCandidates.toList())
            h.events += ExperimentalFleetEvent.Deployment(1, h.queries.last(), ExperimentalFleetDeploymentResult.Discarded)
            h.service.pump(1, 0)
            assertFalse(h.service.request(endpoint) { ProgramRuntimeActorCommand.FileSystemGeneration(it) }.isDone)
        }

    @Test
    fun `failed install waits for disposal and cleanup does not block flushing a full outbox`() =
        Harness(maximumRequests = 2).use { h ->
            val endpoint = h.register(1)
            val install =
                h.service.request(endpoint) {
                    ProgramRuntimeActorCommand.Deploy(it, "/home/test", VmExecutableRevision.Absent, ProgramDeploymentToken(72))
                }
            h.service.pump(0, 1)
            val request = h.queries.single()
            assertTrue(h.service.grantFrame(endpoint, 1, 30))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(
                    endpoint,
                    1,
                    listOf(ProgramRuntimeActorEffect.RedstoneInput(0)),
                    { _, _ -> },
                ),
            )
            h.events +=
                ExperimentalFleetEvent.Deployment(
                    1,
                    request,
                    ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.BUSY),
                )
            h.service.pump(1, 0)
            assertFalse(install.isDone)
            repeat(3) { h.service.pump(0, 1) }
            assertEquals(listOf(72L), h.discardedCandidates.toList())
            h.events += ExperimentalFleetEvent.Deployment(1, h.queries.last(), ExperimentalFleetDeploymentResult.Discarded)
            h.service.pump(1, 0)
            assertEquals(
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.CONFLICT)),
                install.join().value,
            )
        }

    @Test
    fun `prepare callback can install with a single request slot`() =
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            val installed =
                h.service.request(endpoint) { ProgramRuntimeActorCommand.PrepareDeployment(it, byteArrayOf(1)) }.thenCompose { reply ->
                    h.service.request(endpoint) {
                        ProgramRuntimeActorCommand.Deploy(
                            it,
                            "/home/test",
                            VmExecutableRevision.Absent,
                            checkNotNull((reply.value as ProgramRuntimeActorValue.DeploymentPrepared).token),
                        )
                    }
                }
            h.service.pump(0, 1)
            h.events += ExperimentalFleetEvent.Deployment(1, h.queries.single(), ExperimentalFleetDeploymentResult.Prepared(73))
            h.service.pump(1, 1)
            h.service.pump(0, 1)
            h.events +=
                ExperimentalFleetEvent.Deployment(
                    1,
                    h.queries.last(),
                    ExperimentalFleetDeploymentResult.Installed(VmExecutableRevision.Present(3)),
                )
            h.service.pump(1, 0)
            assertEquals(ProgramRuntimeActorValue.DeployedRevision(VmExecutableRevision.Present(3)), installed.join().value)
        }

    @Test
    fun `world observations preserve successive output updates before final credit settlement`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val actions = mutableListOf<ProgramWorldRequest>()
            var done = false
            h.service.grantFrame(endpoint, 1, 30)
            h.service.submitTurn(
                endpoint,
                1,
                emptyList(),
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) = error("unexpected addon")

                    override fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult {
                        assertFalse(done)
                        actions += request
                        return ProgramWorldResult((request as? ProgramWorldRequest.Redstone)?.packed)
                    }

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) {
                        assertEquals(null, failure)
                        done = true
                    }
                },
            )

            fun output(
                id: Long,
                side: Int,
                value: Int,
            ) = VmHostRequest(id, CapabilityIdentity("compukter", "redstone", 1, 0), 6, listOf(VmValue.I32(side), VmValue.I32(value)))
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.HostRequestBatch(listOf(output(1, 0, 5), output(2, 1, 7))))
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.HostRequestBatch(listOf(output(3, 2, 3))))
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 3, false)
            h.service.pump(8, 0)
            assertEquals(
                listOf(5 or (7 shl 5), 5 or (7 shl 5) or (3 shl 10)),
                actions.map { assertIs<ProgramWorldRequest.Redstone>(it).packed },
            )
            assertTrue(done)
            assertTrue(h.observedEvents.isEmpty())
        }

    @Test
    fun `malformed world observation fails only its actor turn`() =
        Harness().use { h ->
            val first = h.register(1)
            val second = h.register(2)
            var failed: Throwable? = null
            var done = false
            h.service.grantFrame(first, 1, 30)
            h.service.grantFrame(second, 1, 30)
            h.service.submitTurn(
                first,
                1,
                emptyList(),
                object : ProgramRuntimeTurnObserver {
                    override fun addonAction(action: ProgramAddonAction) = Unit

                    override fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult = error("invalid request reached world")

                    override fun complete(
                        reply: ProgramRuntimeActorReply?,
                        failure: Throwable?,
                    ) {
                        failed = failure
                    }
                },
            )
            h.service.submitTurn(second, 1, emptyList()) { _, failure -> done = failure == null }
            h.events +=
                ExperimentalFleetEvent.Outcome(
                    1,
                    1,
                    VmOutcome.HostRequestBatch(listOf(VmHostRequest(1, CapabilityIdentity("compukter", "sound", 1, 0), 0, emptyList()))),
                )
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 3, false)
            h.events += ExperimentalFleetEvent.Quiescent(2, 1, 3, false)
            h.service.pump(8, 0)
            assertIs<IllegalArgumentException>(failed)
            assertTrue(done)
            assertTrue(h.observedEvents.isEmpty())
        }

    @Test
    fun `ordered reply prefixes free capacity for the execution grant`() =
        Harness(maximumRequests = 2).use { h ->
            val endpoint = h.register(1)
            val replies = (1L..3L).map { ProgramAddonCompletion(VmHostRequestIdentity(1, it), HostResponse.UnitSuccess) }
            assertTrue(h.service.grantFrame(endpoint, 1, 20))
            assertEquals(
                2,
                h.service.submitHostCompletions(endpoint, replies.map { ProgramRuntimeHostCompletion.Reply(it.identity, it.response) }),
            )
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(endpoint, 1, emptyList()) { _, _ -> })
            h.service.pump(0, 2)
            assertEquals(
                1,
                h.service.submitHostCompletions(
                    endpoint,
                    replies.drop(2).map {
                        ProgramRuntimeHostCompletion.Reply(it.identity, it.response)
                    },
                ),
            )
            var completed = false
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(endpoint, 1, emptyList()) { _, failure -> completed = failure == null },
            )
            h.service.pump(0, 2)
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 3, false)
            h.service.pump(8, 0)
            assertTrue(completed)
            assertEquals(replies.map { it.identity }, h.replies)
        }

    @Test
    fun `machine error fails its turn and queries while another machine completes in the same pump`() =
        Harness().use { h ->
            val first = h.register(1, leased = true)
            val second = h.register(2)
            var failed: Throwable? = null
            var completed = false
            assertTrue(h.service.grantFrame(first, 1, 20))
            assertTrue(h.service.grantFrame(second, 1, 20))
            h.service.submitTurn(first, 1, emptyList()) { _, failure -> failed = failure }
            h.service.submitTurn(second, 1, emptyList()) { reply, failure -> completed = reply != null && failure == null }
            val query = h.service.request(first, ProgramRuntimeActorCommand::FileSystemGeneration)
            h.events += ExperimentalFleetEvent.Error(1, "invalid host response")
            h.events += ExperimentalFleetEvent.Quiescent(2, 1, 3, false)
            h.service.pump(8, 0)
            assertIs<ProgramRuntimeActorFailedException>(failed)
            assertTrue(query.isCompletedExceptionally)
            assertTrue(completed)
            assertFalse(h.service.grantFrame(first, 2, 20))
            assertTrue(h.service.grantFrame(second, 2, 20))
            assertEquals(7L, checkNotNull(h.leases[1]).closeAsync().get(5, TimeUnit.SECONDS))
            h.service.pump(8, 0) // Reap completed close entries, then join the original lease again.
            assertEquals(7L, checkNotNull(h.leases[1]).closeAsync().get(5, TimeUnit.SECONDS))
            h.events += ExperimentalFleetEvent.Error(1, "stale failure")
            h.service.pump(8, 0)
            assertEquals(listOf(1L), h.closedMachines)
        }

    @Test
    fun `native lease returns payload reservation when final reply entry admission is full`() =
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1, leased = true)
            h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            val identity = VmHostRequestIdentity(1, 8)
            val request =
                ProgramActorHibernation(
                    10,
                    byteArrayOf(),
                    listOf(
                        ProgramRuntimeActorEffect.CompleteAddons(listOf(ProgramAddonCompletion(identity, HostResponse.UnitSuccess))),
                    ),
                )
            val lease = checkNotNull(h.leases[1])
            repeat(2) { assertFailsWith<ProgramActorCloseDeferredException> { lease.reserveClose(request) } }
            assertTrue(h.replies.isEmpty())
            h.service.pump(0, 1)
            assertEquals(7L, lease.hibernateAsync(request).get(5, TimeUnit.SECONDS))
            assertEquals(listOf(identity), h.captureReplies[1])
        }

    @Test
    fun `native lease drains final addon reply before capture without a grant or owner pump`() =
        Harness().use { h ->
            h.register(1, leased = true)
            val identity = VmHostRequestIdentity(1, 8)
            val effect = ProgramRuntimeActorEffect.CompleteAddons(listOf(ProgramAddonCompletion(identity, HostResponse.UnitSuccess)))
            val request = ProgramActorHibernation(10, byteArrayOf(4), listOf(effect))
            assertEquals(7L, checkNotNull(h.leases[1]).hibernateAsync(request).get(5, TimeUnit.SECONDS))
            assertEquals(listOf(identity), h.captureReplies[1])
            assertTrue(h.grants.isEmpty())
            assertEquals(listOf(1L), h.closedMachines)
        }

    @Test
    fun `native lease rejects combined checkpoint and reply bytes without retaining a prefix`() {
        val identity = VmHostRequestIdentity(1, 8)
        val charge = NativeFleetOutbox.Command.Reply(identity, HostResponse.UnitSuccess).retainedBytes
        Harness(byteLimits = NativeFleetOutboxLimits(8554 + charge - 1, 8554 + charge - 1)).use { h ->
            val endpoint = h.register(1, leased = true)
            val effect = ProgramRuntimeActorEffect.CompleteAddons(listOf(ProgramAddonCompletion(identity, HostResponse.UnitSuccess)))
            val request = ProgramActorHibernation(10, byteArrayOf(), listOf(effect))
            val lease = checkNotNull(h.leases[1])
            assertFailsWith<ProgramActorCloseDeferredException> { lease.reserveClose(request) }
            h.service.pump(0, 10)
            assertTrue(h.replies.isEmpty())
            assertTrue(h.service.grantFrame(endpoint, 10, 8))
            assertEquals(7L, lease.closeAsync().get(5, TimeUnit.SECONDS))
            assertTrue(h.captured.isEmpty())
        }
    }

    @Test
    fun `native lease can discard saved execution under the scalar control budget`() =
        Harness(byteLimits = NativeFleetOutboxLimits(64, 64)).use { h ->
            h.register(1, leased = true)
            val request = ProgramActorHibernation(10, byteArrayOf(4), emptyList(), saveExecution = false)
            assertEquals(7L, checkNotNull(h.leases[1]).hibernateAsync(request).get(5, TimeUnit.SECONDS))
            assertTrue(h.captured.isEmpty())
            assertEquals(listOf(1L), h.discardedMachines)
            assertEquals(listOf(1L), h.closedMachines)
        }

    @Test
    fun `checkpoint worker copies addon state and closes without owner pumping`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val addon = byteArrayOf(3, 4)
            val pending = checkNotNull(h.service.hibernate(endpoint, 10, addon))
            addon[0] = 99
            pending.cancel(false)
            assertEquals(7L, checkNotNull(h.service.stop(endpoint)).get(5, TimeUnit.SECONDS))
            val checkpoint =
                ru.lazyhat.compukters.core.device.runtime.program.ProgramHostCheckpoint
                    .decode(checkNotNull(h.captured[1]))
            assertContentEquals(byteArrayOf(3, 4), checkpoint.addonState)
            assertEquals(listOf(1L), h.recoveredMachines)
            assertEquals(listOf(1L), h.closedMachines)
            assertEquals(emptyList(), h.observed)
        }

    @Test
    fun `checkpoint admission refusal preserves actor and stale ticks refuse before fencing`() =
        Harness(byteLimits = NativeFleetOutboxLimits(128, 128)).use { h ->
            val endpoint = h.register(1)
            assertTrue(h.service.grantFrame(endpoint, 10, 8))
            assertFailsWith<IllegalArgumentException> { h.service.hibernate(endpoint, 9, byteArrayOf()) }
            val refused = checkNotNull(h.service.hibernate(endpoint, 10, ByteArray(128)))
            assertFailsWith<ExecutionException> { refused.get(5, TimeUnit.SECONDS) }
            assertTrue(h.service.grantFrame(endpoint, 11, 8))
            assertEquals(7L, checkNotNull(h.service.stop(endpoint)).get(5, TimeUnit.SECONDS))
            assertTrue(h.captured.isEmpty())
        }

    @Test
    fun `busy checkpoint rotates so another closing machine completes`() =
        Harness().use { h ->
            val first = h.register(1)
            val second = h.register(2)
            h.busyCaptureFor = 1
            val waiting = checkNotNull(h.service.hibernate(first, 10, byteArrayOf()))
            assertEquals(7L, checkNotNull(h.service.hibernate(second, 10, byteArrayOf())).get(5, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            assertEquals(listOf(1L, 2L), h.recoveredMachines.sorted())
            h.busyCaptureFor = null
            assertEquals(7L, waiting.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), h.recoveredMachines.sorted())
        }

    @Test
    fun `checkpoint failure closes recovered session and returns byte admission`() =
        Harness(byteLimits = NativeFleetOutboxLimits(8554, 8554)).use { h ->
            h.failedCaptureFor = 1
            val first = h.register(1)
            val failed = checkNotNull(h.service.hibernate(first, 10, byteArrayOf()))
            assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
            assertEquals(listOf(1L), h.closedMachines)
            val second = h.register(2)
            assertEquals(7L, checkNotNull(h.service.hibernate(second, 10, byteArrayOf())).get(5, TimeUnit.SECONDS))
        }

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
        val hosts = mutableMapOf<Long, ProgramRuntimeHost>()
        val compilationResults = CopyOnWriteArrayList<Triple<Long, Long, ComputerCompilationOutcome>>()

        @Volatile var busyCompilation = false
        val queries = CopyOnWriteArrayList<Long>()
        val discardedCandidates = CopyOnWriteArrayList<Long>()
        val observed = mutableListOf<VmActorEndpoint>()
        val observedEvents = mutableListOf<ExperimentalFleetEvent>()
        val closedMachines = CopyOnWriteArrayList<Long>()
        val replies = CopyOnWriteArrayList<VmHostRequestIdentity>()

        val leases = mutableMapOf<Long, ProgramRuntimeActorLease>()
        val captureReplies = ConcurrentHashMap<Long, List<VmHostRequestIdentity>>()
        val discardedMachines = CopyOnWriteArrayList<Long>()
        val captured = ConcurrentHashMap<Long, ByteArray>()
        val recoveredMachines = CopyOnWriteArrayList<Long>()

        @Volatile var busyCaptureFor: Long? = null

        @Volatile var failedCaptureFor: Long? = null

        @Volatile var busyReplies = false
        private val endpoints = mutableListOf<VmActorEndpoint>()
        private val stops = ConcurrentHashMap<Long, ExperimentalFleetEvent>()
        private val checkpointPrefixes = ConcurrentHashMap<Long, ExperimentalFleetEvent>()
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

                    "completeCompilationArtifact", "completeCompilationFailure" -> {
                        if (busyCompilation) {
                            throw ru.lazyhat.compukters.lang.runtime.vm
                                .VmFleetBusyException("full")
                        }
                        val result =
                            if (method.name ==
                                "completeCompilationArtifact"
                            ) {
                                ComputerCompilationOutcome.Success(args!![2] as ByteArray)
                            } else {
                                ComputerCompilationOutcome.Failure(args!![2] as String)
                            }
                        compilationResults += Triple(args[0] as Long, args[1] as Long, result)
                        null
                    }

                    "filesystemGeneration" -> {
                        queries += args!![1] as Long
                        null
                    }

                    "terminalUpdate", "fileStat", "fileList", "fileRead", "prepareDeployment",
                    "deploymentRevision", "installDeployment",
                    -> {
                        queries += args!![1] as Long
                        null
                    }

                    "discardDeployment" -> {
                        queries += args!![1] as Long
                        discardedCandidates += args[2] as Long
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

                    "detachForCheckpoint" -> {
                        val machine = args!![0] as Long
                        checkpointPrefixes[machine] =
                            ExperimentalFleetEvent.Outcome(
                                machine,
                                args[1] as Long,
                                VmOutcome.HostRequestBatch(
                                    listOf(VmHostRequest(1, CapabilityIdentity("app", "entry", 1, 2), 0, emptyList())),
                                ),
                            )
                        stops[machine] = ExperimentalFleetEvent.Detached(machine, state = ExperimentalFleetDetachedState(listOf(0), 0))
                        null
                    }

                    "pollLifecycle" -> {
                        checkpointPrefixes.remove(args!![0] as Long) ?: stops.remove(args[0] as Long)
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
            leased: Boolean = false,
            compilerRouter: CompilerCompletionRouter? = null,
        ): VmActorEndpoint {
            val session =
                Proxy.newProxyInstance(ProgramVmSession::class.java.classLoader, arrayOf(ProgramVmSession::class.java)) { _, method, args ->
                    when (method.name) {
                        "transferToFleet" -> {
                            machine
                        }

                        "recoverFromFleet" -> {
                            check(Thread.currentThread() !== owner)
                            recoveredMachines += machine
                            null
                        }

                        "checkpoint" -> {
                            check(Thread.currentThread() !== owner)
                            if (machine == busyCaptureFor) {
                                throw ru.lazyhat.compukters.lang.runtime.vm
                                    .VmStoreBusyException("busy")
                            }
                            if (machine == failedCaptureFor) {
                                throw ru.lazyhat.compukters.lang.runtime.vm
                                    .VmBridgeException("capture failed")
                            }
                            captureReplies[machine] = replies.toList()
                            captured[machine] = args!![0] as ByteArray
                            null
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

                    override fun discardCheckpoint() {
                        discardedMachines += machine
                    }
                }
            val host =
                ProgramRuntimeHost(factory, computerId = computerId, compilerRouter = compilerRouter, addonCapabilitySchemas = schemas)
            hosts[machine] = host
            host.start(byteArrayOf())
            host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(computerId, epoch)
            if (leased) {
                leases[machine] = checkNotNull(service.attach(endpoint, machine, host))
            } else {
                assertTrue(service.register(endpoint, machine, host))
            }
            endpoints += endpoint
            return endpoint
        }

        override fun close() {
            endpoints.forEach { service.stop(it) }
            service.close()
        }
    }
}
