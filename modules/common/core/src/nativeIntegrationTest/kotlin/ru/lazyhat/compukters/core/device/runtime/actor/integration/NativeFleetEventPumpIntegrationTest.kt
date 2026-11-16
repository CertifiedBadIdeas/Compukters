/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor.integration

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.ActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetCommandRequests
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetDispatcher
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetEventPump
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetOutbox
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramDeploymentFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramDeploymentToken
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorEffect
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeFrameCredit
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeHostCompletion
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeTurnObserver
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationAddress
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationOutcome
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationRequest
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompiler
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilerCompletion
import ru.lazyhat.compukters.core.device.runtime.program.CommandBatchProgramHost
import ru.lazyhat.compukters.core.device.runtime.program.NativeProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFileSystemLaunchContext
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.core.device.runtime.program.integration.ProgramRuntimeHostIntegrationTest
import ru.lazyhat.compukters.core.device.runtime.program.integration.installFfmRuntime
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetTimer
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFleetEventPumpIntegrationTest {
    @Test
    fun `native command inspection charges each command and rejects batches larger than the tick budget`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.commands.fixture"))).readBytes()
        for (budget in listOf(1, 2)) {
            val schema = CommandBatchProgramHost.SCHEMA
            val host =
                ProgramRuntimeHost(
                    NativeProgramVmSessionFactory(capabilitySchemas = listOf(schema)),
                    addonCapabilitySchemas = listOf(schema),
                )
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            val config =
                ExperimentalFleetConfig(
                    1,
                    2,
                    2,
                    1_000_000,
                    100_000,
                    budget,
                    128,
                    inspection = NativeFleetCommandRequests.INSPECTION,
                    coordinatedTurns = true,
                )
            VmRuntime.openExperimentalFleet(config).use { fleet ->
                val machine = host.transferToFleet(fleet, 0)
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(361, budget.toLong()), 1)
                NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, event ->
                    check(event !is ExperimentalFleetEvent.Inspection) { "command cost escaped to external owner" }
                }, onActorClosing = {}).use { service ->
                    assertTrue(service.register(endpoint, machine, host))
                    val pending = mutableListOf<ProgramAddonCompletion>()
                    val dispatchTicks = mutableListOf<Long>()
                    for (tick in 1L..20L) {
                        if (host.state is ProgramRuntimeState.Halted || host.state is ProgramRuntimeState.Failed) break
                        assertTrue(service.grantFrame(endpoint, tick, 1_000_000))

                        fun advance(continuation: Boolean) {
                            val result = CompletableFuture<ProgramRuntimeActorReply>()
                            val effects =
                                if (pending.isEmpty()) {
                                    emptyList()
                                } else {
                                    listOf(
                                        ProgramRuntimeActorEffect.CompleteAddons(pending.toList()),
                                    )
                                }
                            pending.clear()
                            val observer =
                                object : ProgramRuntimeTurnObserver {
                                    override fun addonAction(action: ProgramAddonAction) {
                                        val request = assertIs<ProgramAddonAction.Request>(action).request
                                        assertEquals(2, CommandBatchCodec.decode(request).size)
                                        dispatchTicks += tick
                                        pending +=
                                            ProgramAddonCompletion(
                                                request.identity,
                                                HostResponse.ByteArraySuccess(
                                                    CommandBatchCodec.encodeResults(
                                                        listOf(
                                                            HostResponse.UnitSuccess,
                                                            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "missing"),
                                                        ),
                                                    ),
                                                ),
                                            )
                                    }

                                    override fun complete(
                                        reply: ProgramRuntimeActorReply?,
                                        failure: Throwable?,
                                    ) {
                                        if (failure == null) result.complete(checkNotNull(reply)) else result.completeExceptionally(failure)
                                    }
                                }
                            if (continuation) {
                                assertTrue(service.continueTurn(endpoint, tick, effects, observer))
                            } else {
                                assertEquals(VmActorSubmission.ACCEPTED, service.submitTurn(endpoint, tick, effects, observer))
                            }
                            awaitBootstrap(service, result)
                        }
                        advance(false)
                        // Return the first reply within this frame. Its second two-command packet
                        // must wait for the next tick, even with Guest instruction credit remaining.
                        var continuations = 0
                        while (pending.isNotEmpty()) {
                            check(++continuations <= 3) { "unexpected command loop" }
                            advance(true)
                        }
                    }
                    if (budget == 1) {
                        assertTrue(dispatchTicks.isEmpty())
                        assertIs<ProgramRuntimeState.Failed>(host.state)
                    } else {
                        assertEquals(2, dispatchTicks.size)
                        assertEquals(2, dispatchTicks.distinct().size)
                        assertEquals(ProgramRuntimeState.Halted(null), host.state)
                    }
                    checkNotNull(service.stop(endpoint)).get(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `native carrier reboots and powers off through the same lease without losing installed files`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val root = Files.createTempDirectory("native-power-control-").toRealPath()
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(341, 342), 1)
        try {
            WorldFileSystemStore.open(root).use { store ->
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 2, 2, 4096, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    NativeFleetActorRuntimeService(fleet, 4, 8, 8, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        val carrier =
                            ActorProgramComputer(
                                service,
                                checkNotNull(service.attachBootable(endpoint, store, bootstrapImage(artifact))),
                                { RedstoneCommitResult.Committed },
                            )

                        fun await(future: CompletableFuture<ProgramRuntimeActorReply>) = awaitBootstrap(service, future)

                        fun request(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand) = await(carrier.request(command))

                        fun resources() =
                            assertIs<ProgramRuntimeActorValue.ResourceSnapshotValue>(
                                request(ProgramRuntimeActorCommand::ResourceSnapshot).value,
                            ).snapshot
                        try {
                            await(carrier.turnOn())
                            val candidate =
                                assertIs<ProgramRuntimeActorValue.DeploymentPrepared>(
                                    request {
                                        ProgramRuntimeActorCommand.PrepareDeployment(it, artifact)
                                    }.value,
                                ).token!!
                            val installed =
                                assertIs<ProgramRuntimeActorValue.DeployedRevision>(
                                    request {
                                        ProgramRuntimeActorCommand.Deploy(it, "/home/retained", VmExecutableRevision.Absent, candidate)
                                    }.value,
                                ).revision
                            assertTrue(service.grantFrame(endpoint, 5, 2048))
                            carrier.serverTick(5)
                            awaitBootstrapFrame(service, endpoint, 6)
                            assertTrue(assertIs<ProgramResourceSnapshot.Available>(resources()).executedInstructions > 0)
                            await(carrier.reboot())
                            repeat(2) { assertEquals(0, assertIs<ProgramResourceSnapshot.Available>(resources()).executedInstructions) }
                            assertEquals(
                                installed,
                                assertIs<ProgramRuntimeActorValue.ExecutableRevisionValue>(
                                    request {
                                        ProgramRuntimeActorCommand.ExecutableRevision(it, "/home/retained")
                                    }.value,
                                ).revision,
                            )
                            val shutdown = await(carrier.shutdown())
                            assertEquals(ProgramRuntimeState.Idle, shutdown.state)
                            assertEquals(ProgramRuntimeState.Idle, carrier.state)
                            assertIs<ProgramResourceSnapshot.Unavailable>(resources())
                            assertEquals(ProgramRuntimeState.Idle, await(carrier.shutdown()).state)
                            assertFalse(service.grantFrame(endpoint, 7, 2048))
                            await(carrier.turnOn())
                            assertEquals(0, assertIs<ProgramResourceSnapshot.Available>(resources()).executedInstructions)
                            await(carrier.reboot(installSystem = true))
                            assertEquals(0, assertIs<ProgramResourceSnapshot.Available>(resources()).executedInstructions)
                            assertIs<VmExecutableRevision.Present>(
                                assertIs<ProgramRuntimeActorValue.ExecutableRevisionValue>(
                                    request {
                                        ProgramRuntimeActorCommand.ExecutableRevision(it, "/home/retained")
                                    }.value,
                                ).revision,
                            )
                            // Closing the original lease still owns the replacement machine and needs no owner pumping.
                            val restarting = carrier.reboot()
                            carrier.closeAsync().get(5, TimeUnit.SECONDS)
                            assertTrue(restarting.isCompletedExceptionally)
                            assertEquals(ProgramRuntimeState.Closed, carrier.state)
                        } finally {
                            service.stop(endpoint)?.get(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native hibernation cancels compiler work and restoration rejects the previous epoch result`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.compilationRuntime.artifact"))).readBytes()
        val resultArtifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val requests = mutableListOf<ComputerCompilationRequest>()
        val cancellations = mutableListOf<ComputerCompilationAddress>()
        val completions = ArrayDeque<ComputerCompilerCompletion>()
        val compiler =
            object : ComputerCompiler {
                override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
                    requests += request
                    return CompilerSubmissionResult.ACCEPTED
                }

                override fun drain(maximum: Int): List<ComputerCompilerCompletion> =
                    List(minOf(maximum, completions.size)) { completions.removeFirst() }

                override fun cancel(address: ComputerCompilationAddress): Boolean {
                    cancellations += address
                    return true
                }
            }
        val router = CompilerCompletionRouter(compiler)
        val root = Files.createTempDirectory("native-compiler-restore-").toRealPath()
        val id = ComputerId.fromLongs(323, 324)
        try {
            WorldFileSystemStore.open(root).use { store ->
                VmRuntime
                    .openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true))
                    .use { fleet ->
                        NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                            fun carrier(endpoint: VmActorEndpoint) =
                                ActorProgramComputer(
                                    service,
                                    checkNotNull(
                                        service.attachBootable(endpoint, store, bootstrapImage(artifact), compilerRouter = router),
                                    ),
                                    { RedstoneCommitResult.Committed },
                                )

                            fun awaitRequests(
                                carrier: ActorProgramComputer,
                                endpoint: VmActorEndpoint,
                                count: Int,
                                firstTick: Long,
                            ): Long {
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                var tick = firstTick
                                while (requests.size < count) {
                                    check(System.nanoTime() < deadline) { "native compiler request did not arrive: ${carrier.state}" }
                                    check(carrier.state !is ProgramRuntimeState.Failed) { "native compiler failed: ${carrier.state}" }
                                    if (service.grantFrame(endpoint, tick, 100_000)) {
                                        carrier.serverTick(tick)
                                        tick++
                                    }
                                    service.pump(32, 32)
                                    Thread.yield()
                                }
                                awaitBootstrapFrame(service, endpoint, tick)
                                return tick
                            }
                            val firstEndpoint = VmActorEndpoint(id, 1)
                            val first = carrier(firstEndpoint)
                            var checkpointTick = 1L
                            try {
                                awaitBootstrap(service, first.turnOn())
                                checkpointTick = awaitRequests(first, firstEndpoint, 1, 1)
                                first.hibernateAsync(checkpointTick).get(5, TimeUnit.SECONDS)
                            } finally {
                                first.closeAsync().get(5, TimeUnit.SECONDS)
                            }
                            val old = requests.single().address
                            assertEquals(listOf(old), cancellations)
                            val nextEndpoint = VmActorEndpoint(id, 2)
                            val next = carrier(nextEndpoint)
                            try {
                                awaitBootstrap(service, next.turnOn(requiredCheckpoint = true))
                                awaitRequests(next, nextEndpoint, 2, checkpointTick + 1)
                                val resumed = requests.last().address
                                assertEquals(old.computerId, resumed.computerId)
                                assertEquals(old.token, resumed.token)
                                assertTrue(old.vmEpoch != resumed.vmEpoch)
                                assertEquals(requests.first().sources, requests.last().sources)
                                completions += ComputerCompilerCompletion(old, ComputerCompilationOutcome.Success(resultArtifact))
                                val observed =
                                    awaitBootstrap(
                                        service,
                                        service.request(
                                            nextEndpoint,
                                        ) { ProgramRuntimeActorCommand.FileStat(it, VmVirtualPath.of("/home/bad")) },
                                    )
                                assertEquals(ProgramRuntimeState.WaitingForCompiler, observed.state)
                                assertEquals(
                                    ProgramRuntimeActorValue.Rejected(
                                        ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.NOT_FOUND),
                                    ),
                                    observed.value,
                                )
                                next.closeAsync().get(5, TimeUnit.SECONDS) // No owner pump needed to cancel the restored compiler job.
                                assertEquals(listOf(old, resumed), cancellations)
                            } finally {
                                // Preserve the original assertion if the backend has already fenced a failed actor.
                                service.stop(nextEndpoint)?.get(5, TimeUnit.SECONDS)
                            }
                        }
                    }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native carrier compiles real Guest sources with typed diagnostics and runs installed children`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.compilationRuntime.artifact"))).readBytes()
        val root = Files.createTempDirectory("native-fleet-compiler-").toRealPath()
        try {
            ProgramRuntimeHostIntegrationTest.TestCompilerService
                .open(
                    root.resolve("compiler"),
                    Path.of(requireNotNull(System.getProperty("compukters.compilerWorker.manifest"))),
                    Path.of(requireNotNull(System.getProperty("compukters.compilerWorker.payload"))),
                ).use { compiler ->
                    // Use the same /home-only capability as the legacy conformance scenario, rather than boot's root capability.
                    val host = ProgramRuntimeHost(sessionFactory = NativeProgramVmSessionFactory(), compilerRouter = compiler.router)
                    assertEquals(ProgramStartResult.Started, host.start(artifact))
                    VmRuntime
                        .openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true))
                        .use { fleet ->
                            NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                                val endpoint = VmActorEndpoint(ComputerId.fromLongs(321, 322), 1)
                                val machine = host.transferToFleet(fleet, 0)
                                assertTrue(service.register(endpoint, machine, host))
                                try {
                                    var tick = 1L
                                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
                                    while (host.state !is ProgramRuntimeState.Halted) {
                                        check(System.nanoTime() < deadline) { "native compilation stalled: ${host.state}" }
                                        check(host.state !is ProgramRuntimeState.Failed) { "native compilation failed: ${host.state}" }
                                        if (service.grantFrame(endpoint, tick, 100_000)) {
                                            assertEquals(
                                                VmActorSubmission.ACCEPTED,
                                                service.submitTurn(endpoint, tick, emptyList(), { _, _ -> }),
                                            )
                                            tick++
                                        }
                                        service.pump(32, 32)
                                        if (host.state == ProgramRuntimeState.WaitingForCompiler) Thread.sleep(1) else Thread.yield()
                                    }
                                    val state =
                                        checkNotNull(
                                            assertIs<ProgramRuntimeActorValue.TerminalStateValue>(
                                                awaitBootstrap(
                                                    service,
                                                    service.request(endpoint) { ProgramRuntimeActorCommand.TerminalFullState(it) },
                                                ).value,
                                            ).state,
                                        )
                                    val text =
                                        buildString {
                                            repeat(state.height) { y ->
                                                val row =
                                                    buildString {
                                                        repeat(
                                                            state.width,
                                                        ) { x -> appendCodePoint(state.cells[y * state.width + x].codePoint) }
                                                    }
                                                append(row.trimEnd()).append('\n')
                                            }
                                        }.trimEnd('\n') + "\n"
                                    assertEquals("42\n42\n42\ncompiler and process Path API ok\n", text)
                                } finally {
                                    checkNotNull(service.stop(endpoint)).get(5, TimeUnit.SECONDS)
                                }
                            }
                        }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native carrier installs with revision checks and reclaims canceled or failed candidates`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val image = bootstrapImage(artifact)
        val root = Files.createTempDirectory("fleet-native-deployment-").toRealPath()
        val id = ComputerId.fromLongs(311, 312)
        try {
            WorldFileSystemStore.open(root).use { store ->
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 2, 2, 4096, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    NativeFleetActorRuntimeService(
                        fleet,
                        4,
                        8,
                        8,
                        {},
                        {},
                        { _, _ -> error("unexpected native event") },
                        onActorClosing = {},
                    ).use { service ->
                        val endpoint = VmActorEndpoint(id, 1)
                        val carrier =
                            ActorProgramComputer(
                                service,
                                checkNotNull(service.attachBootable(endpoint, store, image)),
                                { RedstoneCommitResult.Committed },
                            )
                        try {
                            awaitBootstrap(service, carrier.turnOn())

                            fun request(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand): ProgramRuntimeActorReply =
                                awaitBootstrap(service, service.request(endpoint, command))

                            fun prepare(): ProgramDeploymentToken =
                                checkNotNull(
                                    assertIs<ProgramRuntimeActorValue.DeploymentPrepared>(
                                        request { ProgramRuntimeActorCommand.PrepareDeployment(it, artifact) }.value,
                                    ).token,
                                )

                            fun rejected(reply: ProgramRuntimeActorReply) = assertIs<ProgramRuntimeActorValue.Rejected>(reply.value).failure

                            fun discard(token: ProgramDeploymentToken) =
                                request { ProgramRuntimeActorCommand.DiscardDeployment(it, token) }.value
                            assertEquals(
                                VmExecutableRevision.Absent,
                                assertIs<ProgramRuntimeActorValue.ExecutableRevisionValue>(
                                    request {
                                        ProgramRuntimeActorCommand.ExecutableRevision(it, "/home/demo")
                                    }.value,
                                ).revision,
                            )
                            assertEquals(
                                ProgramRuntimeActorFailure.Verification,
                                rejected(
                                    request {
                                        ProgramRuntimeActorCommand.PrepareDeployment(it, byteArrayOf(1, 2, 3))
                                    },
                                ),
                            )
                            val first = prepare()
                            val installed =
                                request { ProgramRuntimeActorCommand.Deploy(it, "/home/demo", VmExecutableRevision.Absent, first) }
                            val revision =
                                assertIs<VmExecutableRevision.Present>(
                                    assertIs<ProgramRuntimeActorValue.DeployedRevision>(installed.value).revision,
                                )
                            assertEquals(revision.generation, installed.fileSystemGeneration)
                            assertEquals(ProgramRuntimeActorValue.Accepted(false), discard(first))
                            assertEquals(
                                revision,
                                assertIs<ProgramRuntimeActorValue.ExecutableRevisionValue>(
                                    request {
                                        ProgramRuntimeActorCommand.ExecutableRevision(it, "/home/demo")
                                    }.value,
                                ).revision,
                            )
                            val file =
                                assertIs<ProgramRuntimeActorValue.FileStatValue>(
                                    request {
                                        ProgramRuntimeActorCommand.FileStat(it, VmVirtualPath.of("/home/demo"))
                                    }.value,
                                )
                            assertTrue(checkNotNull(file.stat).metadata.executable)
                            repeat(6) {
                                val stale = prepare()
                                assertEquals(
                                    ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.CONFLICT),
                                    rejected(
                                        request {
                                            ProgramRuntimeActorCommand.Deploy(it, "/home/demo", VmExecutableRevision.Absent, stale)
                                        },
                                    ),
                                )
                                assertEquals(ProgramRuntimeActorValue.Accepted(false), discard(stale))
                            }
                            val replacement = prepare()
                            assertIs<ProgramRuntimeActorValue.DeployedRevision>(
                                request {
                                    ProgramRuntimeActorCommand.Deploy(it, "/home/demo", revision, replacement)
                                }.value,
                            )
                            val invalidPath = prepare()
                            assertIs<ProgramRuntimeActorFailure.InvalidRequest>(
                                rejected(
                                    request {
                                        ProgramRuntimeActorCommand.Deploy(it, "../escape", VmExecutableRevision.Absent, invalidPath)
                                    },
                                ),
                            )
                            assertEquals(ProgramRuntimeActorValue.Accepted(false), discard(invalidPath))
                            val missingParent = prepare()
                            assertEquals(
                                ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.FILESYSTEM),
                                rejected(
                                    request {
                                        ProgramRuntimeActorCommand.Deploy(it, "/missing/demo", VmExecutableRevision.Absent, missingParent)
                                    },
                                ),
                            )
                            assertEquals(ProgramRuntimeActorValue.Accepted(false), discard(missingParent))
                            repeat(6) {
                                val canceled = service.request(endpoint) { ProgramRuntimeActorCommand.PrepareDeployment(it, artifact) }
                                canceled.cancel(false)
                                // Two FIFO observations cross both prepare publication and its disposal admission.
                                repeat(2) { request(ProgramRuntimeActorCommand::FileSystemGeneration) }
                            }
                            val remaining = prepare()
                            assertEquals(ProgramRuntimeActorValue.Accepted(true), discard(remaining))
                            assertEquals(ProgramRuntimeActorValue.Accepted(false), discard(remaining))
                            val held = List(4) { prepare() }
                            assertEquals(
                                ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.ADMISSION),
                                rejected(
                                    request {
                                        ProgramRuntimeActorCommand.PrepareDeployment(it, artifact)
                                    },
                                ),
                            )
                            held.forEach { assertEquals(ProgramRuntimeActorValue.Accepted(true), discard(it)) }
                            val privateToken = prepare()
                            val otherEndpoint = VmActorEndpoint(ComputerId.fromLongs(313, 314), 1)
                            val other =
                                ActorProgramComputer(
                                    service,
                                    checkNotNull(service.attachBootable(otherEndpoint, store, image)),
                                    { RedstoneCommitResult.Committed },
                                )
                            try {
                                awaitBootstrap(service, other.turnOn())
                                val crossMachine =
                                    awaitBootstrap(
                                        service,
                                        service.request(otherEndpoint) {
                                            ProgramRuntimeActorCommand.Deploy(it, "/home/stolen", VmExecutableRevision.Absent, privateToken)
                                        },
                                    )
                                assertIs<ProgramRuntimeActorFailure.InvalidRequest>(rejected(crossMachine))
                                assertEquals(ProgramRuntimeActorValue.Accepted(true), discard(privateToken))
                            } finally {
                                other.closeAsync().get(5, TimeUnit.SECONDS)
                            }
                            // Close drains an accepted prepare without requiring owner pumping or leaking its candidate.
                            val pending = service.request(endpoint) { ProgramRuntimeActorCommand.PrepareDeployment(it, artifact) }
                            carrier.hibernateAsync(1).get(5, TimeUnit.SECONDS)
                            assertTrue(pending.isCompletedExceptionally)
                        } finally {
                            carrier.closeAsync().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
                // Installed executable survives native checkpoint/close and ordinary restoration.
                ProgramRuntimeHost(NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))).use { host ->
                    assertNotNull(host.prepareRestoreBoot(2))
                    assertNotNull(host.executableRevision("/home/demo"))
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native carrier boots installed init and restores addon state before native activation`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val image = bootstrapImage(artifact)
        val root = Files.createTempDirectory("fleet-native-boot-").toRealPath()
        val owner = Thread.currentThread()
        val id = ComputerId.fromLongs(301, 302)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val config = ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true)
                VmRuntime.openExperimentalFleet(config).use { fleet ->
                    NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        var restored = 0

                        fun addon(endpoint: VmActorEndpoint) =
                            object : ru.lazyhat.compukters.api.addon.ProgramAddonHost {
                                override val capabilitySchemas = emptyList<HostCapabilitySchema>()

                                override fun dispatch(request: ru.lazyhat.compukters.api.addon.ProgramAddonRequest) =
                                    error("no addon calls")

                                override fun reset() {
                                    check(Thread.currentThread() === owner)
                                }

                                override fun checkpoint() = byteArrayOf(7)

                                override fun restoreCheckpoint(state: ByteArray) {
                                    check(Thread.currentThread() === owner)
                                    assertContentEquals(byteArrayOf(7), state)
                                    assertFalse(service.grantFrame(endpoint, 0, 100))
                                    restored++
                                }
                            }
                        val endpoint = VmActorEndpoint(id, 1)
                        val first =
                            ActorProgramComputer(
                                service,
                                checkNotNull(service.attachBootable(endpoint, store, image)),
                                { RedstoneCommitResult.Committed },
                                addon = addon(endpoint),
                            )
                        try {
                            val boot = awaitBootstrap(service, first.turnOn())
                            assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(boot.value).result)
                            assertTrue(service.grantFrame(endpoint, 1, 100_000))
                            first.serverTick(1)
                            // Admission of the next frame proves the first frame settled; unload cancels its unused credit.
                            awaitBootstrapFrame(service, endpoint, 2)
                            first.hibernateAsync(2).get(5, TimeUnit.SECONDS)
                        } finally {
                            first.closeAsync().get(5, TimeUnit.SECONDS)
                        }
                        val next = VmActorEndpoint(id, 2)
                        val second =
                            ActorProgramComputer(
                                service,
                                checkNotNull(service.attachBootable(next, store, image)),
                                { RedstoneCommitResult.Committed },
                                addon = addon(next),
                            )
                        try {
                            awaitBootstrap(service, second.turnOn(requiredCheckpoint = true))
                            assertEquals(1, restored)
                            for (tick in 1L..4L) {
                                if (second.state is ProgramRuntimeState.Halted) break
                                awaitBootstrapFrame(service, next, tick)
                                second.serverTick(tick)
                                awaitBootstrap(service, second.request(ProgramRuntimeActorCommand::FileSystemGeneration))
                            }
                            assertIs<ProgramRuntimeState.Halted>(second.state)
                        } finally {
                            second.closeAsync().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unloading a prepared native bootstrap preserves its unconsumed checkpoint without owner pumping`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val image = bootstrapImage(artifact)
        val root = Files.createTempDirectory("fleet-bootstrap-unload-").toRealPath()
        val id = ComputerId.fromLongs(303, 304)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                ProgramRuntimeHost(factory).use { host ->
                    assertEquals(ProgramStartResult.Started, host.startBoot())
                    assertTrue(host.hibernate(1, byteArrayOf(7)))
                }
                val config = ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true)
                VmRuntime.openExperimentalFleet(config).use { fleet ->
                    NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        val endpoint = VmActorEndpoint(id, 1)
                        val lease = checkNotNull(service.attachBootable(endpoint, store, image))
                        val prepared =
                            awaitBootstrap(service, service.request(endpoint) { ProgramRuntimeActorCommand.RestoreOrBoot(it, 10) })
                        assertIs<ProgramRuntimeActorValue.RestorationPrepared>(prepared.value)
                        val replacement = service.request(endpoint) { ProgramRuntimeActorCommand.Start(it, artifact) }
                        val refusedImmediately = replacement.isCompletedExceptionally
                        if (!refusedImmediately) lease.closeAsync().get(5, TimeUnit.SECONDS)
                        assertTrue(
                            refusedImmediately,
                            "prepared restoration must reject standalone replacement before retaining artifact work",
                        )
                        lease
                            .hibernateAsync(
                                ru.lazyhat.compukters.core.device.runtime.actor
                                    .ProgramActorHibernation(10, byteArrayOf(99), emptyList()),
                            ).get(5, TimeUnit.SECONDS)
                    }
                }
                ProgramRuntimeHost(factory).use { restored ->
                    assertContentEquals(byteArrayOf(7), checkNotNull(restored.prepareRestoreBoot(20)).addonState)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native restoration rebind failure resets owner resources before cold boot`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val image = bootstrapImage(artifact)
        val root = Files.createTempDirectory("fleet-bootstrap-rebind-").toRealPath()
        val id = ComputerId.fromLongs(305, 306)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                ProgramRuntimeHost(factory).use { host ->
                    host.startBoot()
                    assertTrue(host.hibernate(1, byteArrayOf(7)))
                }
                val config = ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true)
                VmRuntime.openExperimentalFleet(config).use { fleet ->
                    NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        var restores = 0
                        var resets = 0
                        val addon =
                            object : ru.lazyhat.compukters.api.addon.ProgramAddonHost {
                                override val capabilitySchemas = emptyList<HostCapabilitySchema>()

                                override fun dispatch(request: ru.lazyhat.compukters.api.addon.ProgramAddonRequest) = error("no calls")

                                override fun reset() {
                                    resets++
                                }

                                override fun restoreCheckpoint(state: ByteArray) {
                                    restores++
                                    error("missing resource")
                                }
                            }
                        val endpoint = VmActorEndpoint(id, 1)
                        val carrier =
                            ActorProgramComputer(
                                service,
                                checkNotNull(service.attachBootable(endpoint, store, image)),
                                { RedstoneCommitResult.Committed },
                                addon = addon,
                            )
                        try {
                            val boot = awaitBootstrap(service, carrier.turnOn(true))
                            assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(boot.value).result)
                            assertEquals(1, restores)
                            assertTrue(resets > 0)
                            assertTrue(service.grantFrame(endpoint, 1, 100))
                        } finally {
                            carrier.closeAsync().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
                ProgramRuntimeHost(factory).use { assertNull(it.prepareRestoreBoot(20)) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitBootstrapFrame(
        service: NativeFleetActorRuntimeService,
        endpoint: VmActorEndpoint,
        tick: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!service.grantFrame(endpoint, tick, 100_000)) {
            check(System.nanoTime() < deadline) { "previous native frame did not settle" }
            service.pump(32, 32)
            Thread.yield()
        }
    }

    private fun <T> awaitBootstrap(
        service: NativeFleetActorRuntimeService,
        future: CompletableFuture<T>,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone) {
            check(System.nanoTime() < deadline) { "native bootstrap stalled" }
            service.pump(32, 32)
            Thread.yield()
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun bootstrapImage(artifact: ByteArray): VmSystemImage {
        val path = "/boot/init".encodeToByteArray()
        val bytes =
            ByteBuffer
                .allocate(32 + path.size + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(1)
                .putInt(path.size)
                .put(path)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0)
                .putLong(artifact.size.toLong())
                .put(artifact)
                .array()
        return VmSystemImage.of(bytes + MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    @Test
    fun `native hibernation drains a physical world confirmation before capture without advancing Guest`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.redstone.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(
                    16,
                ).order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("fleet-world-confirm-checkpoint-").toRealPath()
        val id = ComputerId.fromLongs(143, 144)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                val host = ProgramRuntimeHost(factory)
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                val config = ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true)
                VmRuntime.openExperimentalFleet(config).use { fleet ->
                    val machine = host.transferToFleet(fleet, 0)
                    fleet.redstoneInput(machine, RedstoneWire.packInput(63, IntArray(6) { 15 }))
                    NativeFleetActorRuntimeService(fleet, 2, 4, 1, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        val endpoint = VmActorEndpoint(id, 1)
                        val lease = checkNotNull(service.attach(endpoint, machine, host))
                        val active =
                            object : ActorRuntimeService by service {
                                override fun request(
                                    endpoint: VmActorEndpoint,
                                    command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
                                ): CompletableFuture<ProgramRuntimeActorReply> {
                                    val requestId = ProgramRuntimeRequestId(1)
                                    check(command(requestId) is ProgramRuntimeActorCommand.RestoreOrBoot)
                                    return CompletableFuture.completedFuture(
                                        ProgramRuntimeActorReply(requestId, ProgramRuntimeState.Running, ProgramRuntimeActorValue.None),
                                    )
                                }
                            }
                        val writes = mutableListOf<Int>()
                        val carrier =
                            ActorProgramComputer(active, lease, { packed ->
                                writes += packed
                                RedstoneCommitResult.Committed
                            })
                        try {
                            carrier.turnOn().join()
                            assertTrue(service.grantFrame(endpoint, 1, 100_000))
                            carrier.serverTick(1)
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (writes.isEmpty()) {
                                check(System.nanoTime() < deadline) { "world request did not arrive" }
                                service.pump(1, 1)
                                Thread.yield()
                            }
                            // One slot accepts the confirmation; the reply remains in the live carrier.
                            assertNull(carrier.tryHibernateAsync(2))
                            service.pump(0, 1)
                            checkNotNull(carrier.tryHibernateAsync(2)).get(5, TimeUnit.SECONDS)
                            assertEquals(1, writes.size)
                            val restored = ProgramRuntimeHost(factory)
                            try {
                                val checkpoint = checkNotNull(restored.prepareRestoreBoot(100))
                                assertEquals(writes.single(), checkpoint.confirmedRedstoneOutput)
                            } finally {
                                restored.close()
                            }
                        } finally {
                            carrier.closeAsync().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native carrier completes redstone and sound through bounded queues without replaying world actions`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        for (capacity in listOf(1, 8)) {
            for (kind in listOf("redstone", "sound")) {
                for (reject in listOf(false, true)) {
                    val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.$kind.fixture"))).readBytes()
                    val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
                    assertEquals(ProgramStartResult.Started, host.start(artifact))
                    val config = ExperimentalFleetConfig(1, 32, 32, 4096, 64, 64, 32, coordinatedTurns = true)
                    VmRuntime.openExperimentalFleet(config).use { fleet ->
                        val machine = host.transferToFleet(fleet, 0)
                        // Fixture input is part of setup; this scenario isolates bounded world reply delivery.
                        fleet.redstoneInput(machine, RedstoneWire.packInput(63, IntArray(6) { 15 }))
                        NativeFleetActorRuntimeService(fleet, 4, 16, capacity, {}, {}, { _, event ->
                            if (event is ExperimentalFleetEvent.Outcome) {
                                check(
                                    event.outcome !is VmOutcome.HostRequestBatch,
                                ) { "world request escaped carrier" }
                            }
                        }, onActorClosing = {}).use { service ->
                            val endpoint = VmActorEndpoint(ComputerId.fromLongs(41, 42), 1)
                            val lease = checkNotNull(service.attach(endpoint, machine, host))
                            // The host is already started/transferred. Automatic bootstrap remains outside this backend.
                            val active =
                                object : ActorRuntimeService by service {
                                    override fun request(
                                        endpoint: VmActorEndpoint,
                                        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
                                    ): CompletableFuture<ProgramRuntimeActorReply> {
                                        val id = ProgramRuntimeRequestId(1)
                                        check(command(id) is ProgramRuntimeActorCommand.RestoreOrBoot)
                                        return CompletableFuture.completedFuture(
                                            ProgramRuntimeActorReply(id, ProgramRuntimeState.Running, ProgramRuntimeActorValue.None),
                                        )
                                    }
                                }
                            val writes = mutableListOf<Int>()
                            val sounds = mutableListOf<SoundRequest>()
                            val carrier =
                                ActorProgramComputer(
                                    active,
                                    lease,
                                    { packed ->
                                        writes += packed
                                        if (reject) {
                                            RedstoneCommitResult.Failed(
                                                HostFailureKind.INPUT_OUTPUT,
                                                "test world failure",
                                            )
                                        } else {
                                            RedstoneCommitResult.Committed
                                        }
                                    },
                                    { requests ->
                                        sounds += requests
                                        if (reject) {
                                            SoundCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, "test world failure")
                                        } else {
                                            SoundCommitResult.Completed(
                                                requests.map {
                                                    it.note ==
                                                        12
                                                },
                                            )
                                        }
                                    },
                                )
                            try {
                                carrier.turnOn().join()
                                var tick = 1L
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                while (carrier.state == ProgramRuntimeState.Running ||
                                    carrier.state == ProgramRuntimeState.WaitingForInput
                                ) {
                                    check(
                                        System.nanoTime() < deadline,
                                    ) { "world continuation stalled: $kind, capacity=$capacity, state=${carrier.state}" }
                                    service.pump(32, 32)
                                    if (service.grantFrame(endpoint, tick, 100_000)) {
                                        carrier.serverTick(tick)
                                        tick++
                                    }
                                    Thread.yield()
                                }
                                if (reject) {
                                    assertIs<ProgramRuntimeState.Failed>(carrier.state)
                                    assertEquals(1, if (kind == "redstone") writes.size else sounds.size)
                                } else {
                                    assertEquals(ProgramRuntimeState.Halted(null), carrier.state)
                                    if (kind == "redstone") {
                                        assertEquals(3, writes.size)
                                        assertEquals(31, RedstoneWire.output(writes.last(), 3))
                                        assertEquals(31, RedstoneWire.output(writes.last(), 4))
                                    } else {
                                        assertEquals(listOf(SoundRequest(12, 100), SoundRequest(24, 50)), sounds)
                                    }
                                }
                            } finally {
                                carrier.closeAsync().get(5, TimeUnit.SECONDS)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `JVM actor lease hibernates without owner pumping and restores its native timer`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("jvm-lease-checkpoint-").toRealPath()
        val id = ComputerId.fromLongs(195, 196)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                val host = ProgramRuntimeHost(factory)
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                assertEquals(ProgramRuntimeState.Running, host.serverTick(1))
                ProgramRuntimeActorService(VmActorSchedulerConfig(workerCount = 1, maximumActors = 1)).use { service ->
                    val lease = checkNotNull(service.attach(VmActorEndpoint(id, 1), host))
                    val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
                    val generation = carrier.hibernateAsync(2, byteArrayOf(6)).get(5, TimeUnit.SECONDS)
                    assertEquals(host.lastClosedFileSystemGeneration, generation)
                }
                val restored = ProgramRuntimeHost(factory)
                val checkpoint = checkNotNull(restored.prepareRestoreBoot(100))
                assertContentEquals(byteArrayOf(6), checkpoint.addonState)
                assertEquals(1L, checkpoint.timers.single().remainingTicks)
                restored.completeRestoration(100)
                assertEquals(ProgramRuntimeState.Running, restored.serverTick(100))
                assertIs<ProgramRuntimeState.Halted>(restored.serverTick(101))
                restored.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native carrier cold boot close removes an unconsumed stored checkpoint`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("fleet-lease-discard-").toRealPath()
        val id = ComputerId.fromLongs(193, 194)
        try {
            WorldFileSystemStore.open(root).use { store ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                val saved = ProgramRuntimeHost(factory)
                assertEquals(ProgramStartResult.Started, saved.start(artifact))
                assertTrue(saved.hibernate(1, byteArrayOf(5)))
                val running = ProgramRuntimeHost(factory)
                assertEquals(ProgramStartResult.Started, running.start(artifact))
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    val machine = running.transferToFleet(fleet, 2)
                    NativeFleetActorRuntimeService(fleet, 1, 4, 4, {}, {}, { _, _ ->
                        error("discard must not pump ordinary events")
                    }, onActorClosing = {}).use { service ->
                        val endpoint = VmActorEndpoint(id, 1)
                        val lease = checkNotNull(service.attach(endpoint, machine, running))
                        val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
                        val closedGeneration = carrier.hibernateAsync(3, byteArrayOf(), saveExecution = false).get(5, TimeUnit.SECONDS)
                        assertEquals(running.lastClosedFileSystemGeneration, closedGeneration)
                        store.flush(id, checkNotNull(closedGeneration))
                        assertNull(factory.restoreBoot())
                    }
                }
            }
            WorldFileSystemStore.open(root).use { reopened ->
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(reopened, id, image))
                assertNull(factory.restoreBoot())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `checkpoint capture uses native output and preserves timers without dispatching observations`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("fleet-host-checkpoint-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val id = ComputerId.fromLongs(191, 192)
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                val host = ProgramRuntimeHost(factory)
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    val machine = host.transferToFleet(fleet, 0)
                    val observer = VmSession.open(artifact)
                    val other = observer.transferToFleet(fleet)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

                    fun next(target: Long? = null): ExperimentalFleetEvent {
                        while (System.nanoTime() < deadline) {
                            (if (target == null) fleet.poll() else fleet.pollLifecycle(target))?.let { return it }
                            Thread.yield()
                        }
                        error("checkpoint event timed out")
                    }
                    fleet.grant(machine, 1, 512)
                    assertTrue(assertIs<ExperimentalFleetEvent.Quiescent>(next()).timersPending)
                    val generation = fleet.eventGeneration()
                    fleet.resourceSnapshot(other, 41)
                    assertTrue(fleet.waitEvents(generation, 5_000))
                    // The JVM host never observes this accepted output confirmation.
                    fleet.confirmRedstoneOutput(machine, 0x123456)
                    val addon = byteArrayOf(3, 4)
                    val owner = Thread.currentThread()
                    NativeFleetActorRuntimeService(
                        fleet,
                        2,
                        4,
                        4,
                        { check(Thread.currentThread() === owner) },
                        {},
                        { _, _ -> error("checkpoint must not pump ordinary world output") },
                        onActorClosing = {},
                    ).use { service ->
                        val endpoint = VmActorEndpoint(id, 1)
                        val lease = checkNotNull(service.attach(endpoint, machine, host))
                        val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
                        val saved = carrier.hibernateAsync(2, addon)
                        addon[0] = 99
                        val savedGeneration = saved.get(5, TimeUnit.SECONDS)
                        assertEquals(host.lastClosedFileSystemGeneration, savedGeneration)
                    }
                    assertEquals(ProgramRuntimeState.Closed, host.state)
                    val preserved = assertIs<ExperimentalFleetEvent.Resources>(next())
                    assertEquals(other, preserved.machine)
                    assertEquals(41L, preserved.request)
                    val restored = ProgramRuntimeHost(factory)
                    val checkpoint = checkNotNull(restored.prepareRestoreBoot(100))
                    assertEquals(0x123456, checkpoint.confirmedRedstoneOutput)
                    assertEquals(listOf(0L), checkpoint.programScopes)
                    assertEquals(1L, checkpoint.timers.single().remainingTicks)
                    assertContentEquals(byteArrayOf(3, 4), checkpoint.addonState)
                    restored.completeRestoration(100)
                    assertEquals(ProgramRuntimeState.Running, restored.serverTick(100))
                    assertIs<ProgramRuntimeState.Halted>(restored.serverTick(101))
                    restored.close()
                    fleet.stop(other, independent = true)
                    while (next(other) !is ExperimentalFleetEvent.Stopped) { }
                    observer.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `actor stop drains accepted commands and closes without owner pumping while ordinary output is full`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)
            VmSession.open(artifact).use { observer ->
                val other = observer.transferToFleet(fleet)
                val generation = fleet.eventGeneration()
                fleet.resourceSnapshot(other, 42)
                assertTrue(fleet.waitEvents(generation, 5_000))
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(41, 42), 1)
                var canceled = false
                NativeFleetActorRuntimeService(
                    fleet,
                    2,
                    1,
                    1,
                    { check(Thread.currentThread() === owner) },
                    {},
                    { _, _ -> error("ordinary output must not be pumped") },
                    onActorClosing = { address ->
                        check(Thread.currentThread() === owner)
                        assertEquals(endpoint, address)
                        canceled = true
                    },
                ).use { service ->
                    assertTrue(service.register(endpoint, machine, host))
                    val query = service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
                    // No pump at all: even the accepted query has not reached Rust yet.
                    val stopped = checkNotNull(service.stop(endpoint))
                    assertTrue(canceled && query.isCompletedExceptionally)
                    assertEquals(0L, stopped.get(5, TimeUnit.SECONDS))
                    assertEquals(ProgramRuntimeState.Closed, host.state)
                    val preserved = assertIs<ExperimentalFleetEvent.Resources>(fleet.poll())
                    assertEquals(other, preserved.machine)
                    assertEquals(42L, preserved.request)
                    fleet.stop(other, independent = true)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        check(System.nanoTime() < deadline)
                        val event = fleet.pollLifecycle(other)
                        if (event is ExperimentalFleetEvent.Stopped) break
                        Thread.yield()
                    }
                }
            }
        }
    }

    @Test
    fun `host releases ownership only after native stop and retains filesystem generation`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)

            fun next(): ExperimentalFleetEvent {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) {
                    fleet.poll()?.let { return it }
                    Thread.yield()
                }
                error("timed out waiting for fleet event")
            }
            val published = fleet.eventGeneration()
            fleet.resourceSnapshot(machine, 1)
            assertTrue(fleet.waitEvents(published, 5_000))
            assertFailsWith<IllegalStateException> {
                host.acknowledgeFleetStop(
                    fleet,
                    ExperimentalFleetEvent.Stopped(machine + 1, emptyList(), 0),
                    ProgramRuntimeState.Idle,
                )
            }
            assertFailsWith<IllegalStateException> { host.close() }
            fleet.stop(machine, independent = true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var stopped: ExperimentalFleetEvent.Stopped? = null
            while (stopped == null) {
                check(System.nanoTime() < deadline)
                stopped = fleet.pollLifecycle(machine) as? ExperimentalFleetEvent.Stopped
                if (stopped == null) Thread.yield()
            }
            host.acknowledgeFleetStop(fleet, stopped, ProgramRuntimeState.Idle)
            assertEquals(ProgramRuntimeState.Idle, host.state)
            assertEquals(stopped.filesystemGeneration, host.lastClosedFileSystemGeneration)
            val older = next() as ExperimentalFleetEvent.Resources
            assertEquals(machine, older.machine)
            assertEquals(1L, older.request)
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            host.close()
        }
    }

    @Test
    fun `live host transfers its timer to rust and resumes it after recovery`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val actions = mutableListOf<ProgramAddonAction>()
        val host =
            ProgramRuntimeHost(NativeProgramVmSessionFactory(), addonLifecyclePort = {
                actions.add(it)
                true
            })
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        assertEquals(ProgramRuntimeState.Running, host.serverTick(1))
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 1)
            assertFailsWith<IllegalStateException> { host.observeFleetLifecycle(fleet, machine + 1, VmOutcome.ProcessEntered(1)) }
            host.observeFleetLifecycle(fleet, machine, VmOutcome.ProcessEntered(1))
            host.observeFleetLifecycle(fleet, machine, VmOutcome.ProcessExited(1))
            assertEquals(listOf(ProgramAddonAction.Started(1), ProgramAddonAction.Stopped(1)), actions)
            assertFailsWith<IllegalStateException> { host.serverTick(2) }
            assertFailsWith<IllegalStateException> { host.shutdown() }
            fleet.detachWithTimers(machine, 2)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var detached: ExperimentalFleetEvent.Detached? = null
            while (detached == null) {
                check(System.nanoTime() < deadline)
                detached = fleet.poll() as? ExperimentalFleetEvent.Detached
                if (detached == null) Thread.yield()
            }
            assertEquals(1L, detached.timers.single().remaining)
            host.recoverFromFleet(fleet, machine, 2, detached.timers)
            assertEquals(ProgramRuntimeState.Running, host.serverTick(2))
            assertTrue(host.serverTick(3) is ProgramRuntimeState.Halted)
        }
        host.close()
    }

    @Test
    fun `filesystem session transfers recovers and checkpoints with its timer`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("fleet-session-integration-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val id = ComputerId.fromLongs(99, 100)
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    fun next(): ExperimentalFleetEvent {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (System.nanoTime() < deadline) {
                            fleet.poll()?.let { return it }
                            Thread.yield()
                        }
                        error("timed out waiting for fleet event")
                    }
                    val session = factory.open(artifact)
                    val invalidTimer = ExperimentalFleetTimer(VmHostRequestIdentity(1, 1), 0, 1, 1)
                    assertFailsWith<VmBridgeException> { session.transferToFleet(fleet, 0, listOf(invalidTimer)) }
                    assertTrue(session.resourceSnapshot().executedInstructions >= 0)
                    val machine = session.transferToFleet(fleet, 0, emptyList())
                    assertFailsWith<IllegalStateException> { session.close() }
                    assertFailsWith<IllegalStateException> { session.recoverFromFleet(fleet, machine + 1) }
                    fleet.grant(machine, 1, 512)
                    assertTrue((next() as ExperimentalFleetEvent.Quiescent).timersPending)
                    fleet.detachWithTimers(machine, 2)
                    val detached = next() as ExperimentalFleetEvent.Detached
                    val timer = detached.timers.single()
                    assertEquals(1L, timer.remaining)
                    session.recoverFromFleet(fleet, machine)
                    val host = byteArrayOf(1, 2, 3)
                    session.checkpoint(host)
                    session.close()
                    val restored = requireNotNull(factory.restoreBoot())
                    assertContentEquals(host, restored.hostState)
                    factory.discardCheckpoint()
                    val restoredMachine = restored.session.transferToFleet(fleet, 100, listOf(timer))
                    fleet.grant(restoredMachine, 100, 512)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.grant(restoredMachine, 101, 512)
                    assertTrue((next() as ExperimentalFleetEvent.Outcome).outcome is VmOutcome.Halted)
                    assertTrue(!(next() as ExperimentalFleetEvent.Quiescent).timersPending)
                    fleet.stop(restoredMachine)
                    assertTrue(next() is ExperimentalFleetEvent.Stopped)
                    restored.session.acknowledgeFleetStop(fleet, restoredMachine)
                    restored.session.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native continuation charges only the newly retired instructions`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema =
            HostCapabilitySchema(
                CapabilityIdentity("app", "entry", 1, 2),
                listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)),
            )
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val session = VmSession.open(artifact, listOf(schema))
            val machine = session.transferToFleet(fleet)
            val credit = ProgramRuntimeFrameCredit(4, 256, Long.MAX_VALUE, true)
            NativeFleetDispatcher(
                fleet,
                8,
                8,
                { check(Thread.currentThread() === owner) },
                { notifications.offer(Unit) },
                events::add,
            ).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean): ExperimentalFleetEvent {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        pump.pumpLifecycle(32, 32, deliver = events::add)
                        pump.pump(32, 32)
                        val index = events.indexOfFirst(predicate)
                        if (index >= 0) return events.removeAt(index)
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.grant(machine, 4, 256)
                val request =
                    (
                        (
                            await {
                                it is ExperimentalFleetEvent.Outcome
                            } as ExperimentalFleetEvent.Outcome
                        ).outcome as VmOutcome.HostRequestBatch
                    ).requests.single()
                val first = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val firstDelta = credit.finishCumulative(first.retired, 256)
                val remaining = credit.remaining
                // The unresolved host request prevents detachment. The machine must become
                // available for commands again once the rejection reaches the owner.
                assertTrue(pump.outbox.offer(machine, listOf(NativeFleetOutbox.Command.DetachWithTimers(4))))
                pump.pump(0, 1)
                await { it is ExperimentalFleetEvent.DetachRejected }
                assertTrue(
                    pump.outbox.offer(
                        machine,
                        listOf(
                            NativeFleetOutbox.Command.Reply(request.identity, HostResponse.UnitSuccess),
                            NativeFleetOutbox.Command.Grant(4, remaining.toInt(), Long.MAX_VALUE),
                        ),
                    ),
                )
                credit.inFlight = true
                pump.pump(0, 2)
                val halted = await { it is ExperimentalFleetEvent.Outcome } as ExperimentalFleetEvent.Outcome
                assertEquals(VmOutcome.Halted(null), halted.outcome)
                val second = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val secondDelta = credit.finishCumulative(second.retired, remaining)
                assertEquals(second.retired, firstDelta + secondDelta)
                assertEquals(256 - second.retired, credit.remaining)
                assertTrue(pump.outbox.offer(machine, listOf(NativeFleetOutbox.Command.Stop)))
                pump.pump(0, 1)
                await { it is ExperimentalFleetEvent.Stopped }
            }
            session.close()
        }
    }

    @Test
    fun `dispatcher stops and detaches without delivering another machines queued output`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32)).use { fleet ->
            val stoppedSession = VmSession.open(artifact)
            val detachedSession = VmSession.open(artifact)
            val observer = VmSession.open(artifact)
            val stoppedMachine = stoppedSession.transferToFleet(fleet)
            val detachedMachine = detachedSession.transferToFleet(fleet)
            val observerMachine = observer.transferToFleet(fleet)
            val initialGeneration = fleet.eventGeneration()
            fleet.resourceSnapshot(observerMachine, 42)
            assertTrue(fleet.waitEvents(initialGeneration, 5_000))
            NativeFleetDispatcher(
                fleet,
                8,
                8,
                { check(Thread.currentThread() === owner) },
                {},
                { error("ordinary output must remain queued") },
            ).use { dispatcher ->
                assertTrue(dispatcher.outbox.offer(stoppedMachine, listOf(NativeFleetOutbox.Command.Stop)))
                assertTrue(dispatcher.outbox.offer(detachedMachine, listOf(NativeFleetOutbox.Command.DetachWithTimers(0))))
                assertEquals(0, dispatcher.pump(0, 2))
                val completed = mutableListOf<ExperimentalFleetEvent>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (completed.size < 2) {
                    check(System.nanoTime() < deadline) { "independent lifecycle stalled behind ordinary output" }
                    dispatcher.pumpLifecycle(2, 2) {
                        check(Thread.currentThread() === owner)
                        completed += it
                    }
                    Thread.yield()
                }
                assertTrue(completed.any { it is ExperimentalFleetEvent.Stopped && it.machine == stoppedMachine })
                assertTrue(completed.any { it is ExperimentalFleetEvent.Detached && it.machine == detachedMachine })
                VmSession.restoreFromFleet(fleet, detachedMachine).close()
                // Nothing from the unrelated VM was discarded or delivered as part of close.
                val pending = fleet.poll() as ExperimentalFleetEvent.Resources
                assertEquals(observerMachine, pending.machine)
                assertEquals(42L, pending.request)
                assertEquals(null, fleet.poll())
                assertTrue(dispatcher.outbox.offer(observerMachine, listOf(NativeFleetOutbox.Command.Stop)))
                dispatcher.pump(0, 1)
                var observerStopped = false
                while (!observerStopped) {
                    check(System.nanoTime() < deadline)
                    dispatcher.pumpLifecycle(1, 1) {
                        assertTrue(it is ExperimentalFleetEvent.Stopped && it.machine == observerMachine)
                        observerStopped = true
                    }
                    Thread.yield()
                }
            }
            stoppedSession.close()
            detachedSession.close()
            observer.close()
        }
    }

    @Test
    fun `native actor service runs timer turns through the carrier contract and stops its host`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(ComputerId.fromLongs(31, 32), 1)
            NativeFleetActorRuntimeService(
                fleet,
                4,
                16,
                16,
                { check(Thread.currentThread() === owner) },
                {},
                { _, _ -> },
                onActorClosing = {},
            ).use { service ->
                assertTrue(service.register(endpoint, machine, host))
                val runtime: ActorRuntimeService = service

                fun <T> await(future: CompletableFuture<T>): T {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!future.isDone) {
                        check(System.nanoTime() < deadline) { "native actor turn stalled" }
                        service.pump(32, 32)
                        Thread.yield()
                    }
                    return future.join()
                }
                val replies =
                    (1L..3L).map { tick ->
                        assertTrue(service.grantFrame(endpoint, tick, 512))
                        val result = CompletableFuture<ProgramRuntimeActorReply>()
                        assertEquals(
                            VmActorSubmission.ACCEPTED,
                            runtime.submitTurn(endpoint, tick, emptyList()) { reply, failure ->
                                check(Thread.currentThread() === owner)
                                if (failure == null) result.complete(checkNotNull(reply)) else result.completeExceptionally(failure)
                            },
                        )
                        await(result).also { assertTrue(!service.grantFrame(endpoint, tick, 512)) }
                    }
                assertEquals(ProgramRuntimeState.Running, replies[0].state)
                assertEquals(0L, replies[1].retiredInstructions)
                assertEquals(ProgramRuntimeState.Halted(null), replies[2].state)
                // The same artifact and tick sequence produce the same retirement count on
                // the existing host path, although only Rust workers execute in this service.
                ProgramRuntimeHost(NativeProgramVmSessionFactory()).use { baseline ->
                    assertEquals(ProgramStartResult.Started, baseline.start(artifact))
                    var retired = 0L
                    for (tick in 1L..3L) {
                        baseline.serverTick(tick, 512)
                        retired += baseline.retiredInstructionsLastTick
                    }
                    assertEquals(replies.sumOf { it.retiredInstructions }, retired)
                    assertEquals(baseline.state, host.state)
                }
                val generation = await(runtime.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration))
                val stopped = await(checkNotNull(service.stop(endpoint)))
                assertEquals(generation.fileSystemGeneration, stopped)
                assertEquals(stopped, host.lastClosedFileSystemGeneration)
                assertEquals(ProgramRuntimeState.Closed, host.state)
            }
        }
    }

    @Test
    fun `native actor streams addon request and continues within the original frame`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema =
            HostCapabilitySchema(
                CapabilityIdentity("app", "entry", 1, 2),
                listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)),
            )
        for (capacity in listOf(1, 16)) {
            val host =
                ProgramRuntimeHost(
                    NativeProgramVmSessionFactory(capabilitySchemas = listOf(schema)),
                    addonCapabilitySchemas = listOf(schema),
                )
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                val machine = host.transferToFleet(fleet, 0)
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(33, 34), 1)
                var request: VmHostRequestIdentity? = null
                NativeFleetActorRuntimeService(fleet, 4, 16, capacity, {}, {}, { address, event ->
                    assertEquals(endpoint, address)
                    if (event is ExperimentalFleetEvent.Outcome) {
                        val outcome = event.outcome
                        check(outcome !is VmOutcome.HostRequestBatch) { "managed addon request delivered twice" }
                    }
                }, onActorClosing = {}).use { service ->
                    assertTrue(service.register(endpoint, machine, host))

                    fun <T> await(future: CompletableFuture<T>): T {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!future.isDone) {
                            check(System.nanoTime() < deadline)
                            service.pump(32, 32)
                            Thread.yield()
                        }
                        return future.join()
                    }
                    assertTrue(service.grantFrame(endpoint, 4, 256))
                    val first = CompletableFuture<ProgramRuntimeActorReply>()
                    assertEquals(
                        VmActorSubmission.ACCEPTED,
                        service.submitTurn(
                            endpoint,
                            4,
                            emptyList(),
                            object : ProgramRuntimeTurnObserver {
                                override fun addonAction(action: ProgramAddonAction) {
                                    val addon = assertIs<ProgramAddonAction.Request>(action).request
                                    assertEquals(0L, addon.programId)
                                    assertTrue(!first.isDone)
                                    check(request == null)
                                    request = addon.identity
                                }

                                override fun complete(
                                    reply: ProgramRuntimeActorReply?,
                                    failure: Throwable?,
                                ) {
                                    if (failure == null) first.complete(checkNotNull(reply)) else first.completeExceptionally(failure)
                                }
                            },
                        ),
                    )
                    val before = await(first)
                    val runtime: ActorRuntimeService = service
                    // Streaming does not settle credit; only the final report permits this continuation.
                    val completion =
                        ProgramRuntimeActorEffect.CompleteAddons(
                            listOf(
                                ProgramAddonCompletion(checkNotNull(request), HostResponse.UnitSuccess),
                            ),
                        )
                    val second = CompletableFuture<ProgramRuntimeActorReply>()
                    val effects =
                        if (capacity == 1) {
                            assertEquals(
                                1,
                                runtime.submitHostCompletions(
                                    endpoint,
                                    completion.completions.map {
                                        ProgramRuntimeHostCompletion.Reply(it.identity, it.response)
                                    },
                                ),
                            )
                            assertFalse(runtime.continueTurn(endpoint, 4, emptyList()) { _, _ -> error("refused grant completed") })
                            service.pump(0, 1) // Deliver the reply before the one-entry queue can admit a grant.
                            emptyList()
                        } else {
                            listOf(completion, completion)
                        }
                    assertTrue(
                        runtime.continueTurn(endpoint, 4, effects) { reply, failure ->
                            if (failure == null) second.complete(checkNotNull(reply)) else second.completeExceptionally(failure)
                        },
                    )
                    val after = await(second)
                    assertEquals(ProgramRuntimeState.Halted(null), after.state)
                    assertTrue(before.retiredInstructions > 0 && after.retiredInstructions > 0)
                    assertTrue(before.retiredInstructions + after.retiredInstructions <= 256)
                    await(checkNotNull(service.stop(endpoint)))
                }
            }
        }
    }

    @Test
    fun `native actor terminal requests report actual acceptance before the next grant`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        for (canonical in listOf(false, true)) {
            val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 1024, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                val machine = host.transferToFleet(fleet, 0)
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(35, 36), 1)
                val owner = Thread.currentThread()
                val checkOwner = { check(Thread.currentThread() === owner) }
                NativeFleetActorRuntimeService(fleet, 4, 16, 16, checkOwner, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                    assertTrue(service.register(endpoint, machine, host))
                    val runtime: ActorRuntimeService = service

                    fun <T> await(future: CompletableFuture<T>): T {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!future.isDone) {
                            check(System.nanoTime() < deadline) { "terminal request stalled" }
                            service.pump(32, 32)
                            Thread.yield()
                        }
                        return future.join()
                    }

                    fun turn(tick: Long): ProgramRuntimeActorReply {
                        assertTrue(service.grantFrame(endpoint, tick, 512))
                        val result = CompletableFuture<ProgramRuntimeActorReply>()
                        assertEquals(
                            VmActorSubmission.ACCEPTED,
                            runtime.submitTurn(endpoint, tick, emptyList()) { reply, failure ->
                                if (failure == null) result.complete(checkNotNull(reply)) else result.completeExceptionally(failure)
                            },
                        )
                        return await(result)
                    }
                    val premature =
                        await(runtime.request(endpoint) { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, "hello".toCharArray()) })
                    assertEquals(
                        ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.CanonicalLine(VmCanonicalLineFailure.NO_PENDING_READ)),
                        premature.value,
                    )
                    assertEquals(ProgramRuntimeState.WaitingForInput, turn(1).state)
                    val full =
                        assertIs<ProgramRuntimeActorValue.TerminalStateValue>(
                            await(runtime.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)).value,
                        ).state!!
                    assertEquals(
                        TerminalUpdate.Unchanged(full.revision),
                        assertIs<ProgramRuntimeActorValue.TerminalUpdateValue>(
                            await(runtime.request(endpoint) { ProgramRuntimeActorCommand.TerminalChangesSince(it, full.revision) }).value,
                        ).update,
                    )
                    if (canonical) {
                        val accepted =
                            await(runtime.request(endpoint) { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, "hello".toCharArray()) })
                        assertEquals(ProgramRuntimeActorValue.Accepted(true), accepted.value)
                        assertEquals(ProgramRuntimeState.Running, accepted.state)
                    } else {
                        assertEquals(
                            ProgramRuntimeActorValue.Accepted(true),
                            await(
                                runtime.request(endpoint) {
                                    ProgramRuntimeActorCommand.SendTerminalText(it, "hello")
                                },
                            ).value,
                        )
                        assertEquals(
                            ProgramRuntimeActorValue.Accepted(true),
                            await(
                                runtime.request(endpoint) {
                                    ProgramRuntimeActorCommand.SendTerminalKey(it, TerminalKey.ENTER, TerminalKeyAction.PRESS, emptySet())
                                },
                            ).value,
                        )
                    }
                    // Input acknowledgements update observations but coordinated mode does not
                    // execute the resumed Guest task until another capacity-owner grant arrives.
                    assertEquals(
                        ProgramRuntimeState.Running,
                        await(runtime.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)).state,
                    )
                    var state: ProgramRuntimeState = host.state
                    for (tick in 2L..128L) {
                        state = turn(tick).state
                        if (state is ProgramRuntimeState.Halted) break
                    }
                    assertEquals(ProgramRuntimeState.Halted(VmValue.I32("hello".hashCode())), state)
                    assertEquals(
                        ProgramRuntimeActorValue.Accepted(false),
                        await(
                            runtime.request(endpoint) {
                                ProgramRuntimeActorCommand.SendTerminalText(it, "after halt")
                            },
                        ).value,
                    )
                    await(checkNotNull(service.stop(endpoint)))
                }
            }
        }
    }

    @Test
    fun `native actor filesystem and resource queries match the original session without execution grants`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val path = VmVirtualPath.of("/bin/sample")
        val encodedPath = path.value.encodeToByteArray()
        val payload =
            ByteBuffer
                .allocate(32 + encodedPath.size + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(1)
                .putInt(
                    encodedPath.size,
                ).put(encodedPath)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0)
                .putLong(artifact.size.toLong())
                .put(artifact)
                .array()
        val image = VmSystemImage.of(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        val root = Files.createTempDirectory("fleet-carrier-files-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val id = ComputerId.fromLongs(37, 38)
                val host = ProgramRuntimeHost(NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image)))
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                val resources = assertIs<ProgramResourceSnapshot.Available>(host.resourceSnapshot())
                val stat = host.fileStat(path)!!
                val listing = host.fileList(VmVirtualPath.of("/bin"), null, 256)!!
                val chunk = host.fileRead(path, 0, 32, stat.metadata.generation)!!
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    val machine = host.transferToFleet(fleet, 0)
                    val endpoint = VmActorEndpoint(id, 1)
                    NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        assertTrue(service.register(endpoint, machine, host))

                        fun <T> await(future: CompletableFuture<T>): T {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (!future.isDone) {
                                check(System.nanoTime() < deadline) { "filesystem request stalled" }
                                service.pump(32, 32)
                                Thread.yield()
                            }
                            return future.join()
                        }
                        val runtime: ActorRuntimeService = service
                        repeat(2) {
                            val reply = await(runtime.request(endpoint, ProgramRuntimeActorCommand::ResourceSnapshot))
                            val snapshot = assertIs<ProgramRuntimeActorValue.ResourceSnapshotValue>(reply.value).snapshot
                            assertEquals(resources.copy(grantedGuestUnits = null, grantedMaintenanceUnits = null), snapshot)
                        }
                        val statReply = runtime.request(endpoint) { ProgramRuntimeActorCommand.FileStat(it, path) }
                        val listReply =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileList(it, VmVirtualPath.of("/bin"), null, 256) }
                        val readReply =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileRead(it, path, 0, 32, stat.metadata.generation) }
                        val stale =
                            runtime.request(endpoint) {
                                ProgramRuntimeActorCommand.FileRead(
                                    it,
                                    path,
                                    0,
                                    32,
                                    stat.metadata.generation + 1,
                                )
                            }
                        val missing =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileStat(it, VmVirtualPath.of("/home/missing")) }
                        assertEquals(stat, assertIs<ProgramRuntimeActorValue.FileStatValue>(await(statReply).value).stat)
                        assertEquals(listing, assertIs<ProgramRuntimeActorValue.FileListValue>(await(listReply).value).listing)
                        assertEquals(chunk, assertIs<ProgramRuntimeActorValue.FileChunkValue>(await(readReply).value).chunk)
                        assertEquals(
                            ProgramRuntimeActorValue.Rejected(
                                ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.STALE_GENERATION),
                            ),
                            await(stale).value,
                        )
                        assertEquals(
                            ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.NOT_FOUND)),
                            await(missing).value,
                        )
                        assertEquals(ProgramRuntimeState.Running, host.state)
                        await(checkNotNull(service.stop(endpoint)))
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native events wake the owner and are delivered on the owner thread`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val owner = Thread.currentThread()
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32)).use { fleet ->
            val session = VmSession.open(artifact)
            val machine = session.transferToFleet(fleet)
            NativeFleetEventPump(
                fleet,
                { check(Thread.currentThread() === owner) },
                { notifications.offer(Unit) },
                events::add,
            ).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (events.none(predicate)) {
                        pump.pump(32)
                        if (events.any(predicate)) break
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.resourceSnapshot(machine, 42)
                await { it is ExperimentalFleetEvent.Resources && it.request == 42L }
                assertEquals(1, events.count { it is ExperimentalFleetEvent.Resources && it.request == 42L })
                fleet.stop(machine)
                await { it is ExperimentalFleetEvent.Stopped && it.machine == machine }
            }
            session.close()
        }
    }
}
