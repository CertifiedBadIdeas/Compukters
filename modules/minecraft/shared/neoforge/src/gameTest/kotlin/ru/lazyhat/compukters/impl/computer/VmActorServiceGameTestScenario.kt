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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.MinecraftServer
import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmExecutionBackend
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.impl.benchmark.VmCapacityCalibrator
import ru.lazyhat.compukters.impl.compat.enqueueCompuktersTask
import ru.lazyhat.compukters.impl.fs.WorldFileSystemStoreRegistry
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

internal object VmActorServiceGameTestScenario {
    fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val service = NeoForgeVmActorServices.service(server)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(603, 1), 1)
        val rom = ComputerGameTestFixtures.processTestRom()
        val root = Files.createTempDirectory("compukters-actor-gametest-")
        val stores =
            WorldFileSystemStoreRegistry(
                opener = WorldFileSystemStore::open,
                flusher = WorldFileSystemStore::flush,
                tombstoner = WorldFileSystemStore::tombstone,
                recoverer = WorldFileSystemStore::recover,
                closer = WorldFileSystemStore::close,
            )
        val store = stores.store(root)
        val lease = requireNotNull(service.attachBootable(endpoint, store, VmSystemImage.of(rom)))
        val computer = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
        var drainCalls = 0
        stores.lifecycle(root).attach(endpoint.computerId, { computer.fileSystemGeneration }) {
            check(server.isSameThread)
            drainCalls++
            if (drainCalls == 1) null else computer.closeAsync()
        }
        // Exercise the same registry and backend factory with an explicitly selected native service.
        // Its world effects and callbacks still belong to the GameTest server's owner thread.
        val nativeRegistry =
            VmActorServiceRegistry<MinecraftServer>(
                checkOwner = { check(it.isSameThread) },
                opener = { ready ->
                    openServerVmRuntime(
                        VmExecutionBackend.NATIVE,
                        VmActorSchedulerConfig(workerCount = 1, maximumActors = 4),
                        ready = ready,
                    )
                },
                calibrator = { VmCapacityCalibrator.start(1, it.runtimeMetrics().backend) },
                enqueueOnOwner = { owner, task ->
                    var enqueued = !owner.isSameThread
                    owner.enqueueCompuktersTask {
                        helper.assertTrue(enqueued, "VM notification executed inline during command admission")
                        task.run()
                    }
                    enqueued = true
                },
            )
        nativeRegistry.start(server)
        val nativeService = nativeRegistry.service(server)
        val nativeEndpoint = VmActorEndpoint(ComputerId.fromLongs(603, 2), 1)
        val nativeLease = requireNotNull(nativeService.attachBootable(nativeEndpoint, store, VmSystemImage.of(rom)))
        val nativeComputer = ActorProgramComputer(nativeService, nativeLease, { RedstoneCommitResult.Committed })
        stores.lifecycle(root).attach(nativeEndpoint.computerId, { nativeComputer.fileSystemGeneration }) { nativeComputer.closeAsync() }
        var nativeTick = -1L

        fun pumpNative() {
            val tick = server.tickCount.toLong()
            if (tick <= nativeTick) return
            nativeTick = tick
            nativeRegistry.tick(server, tick)
            nativeComputer.serverTick(tick)
            nativeRegistry.afterTick(server)
        }
        val nativeBoot = nativeComputer.turnOn()
        var nativeDeliveredOnServer = false
        val nativeObserved = nativeBoot.thenAccept { nativeDeliveredOnServer = server.isSameThread }
        val boot = computer.turnOn()
        var deliveredOnServer = false
        val observed = boot.thenAccept { deliveredOnServer = server.isSameThread }
        var terminal: CompletableFuture<ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply>? = null
        var nativeTerminal: CompletableFuture<ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply>? = null
        var closed: CompletableFuture<Void>? = null
        helper
            .startSequence()
            .thenWaitUntil {
                pumpNative()
                helper.assertTrue(observed.isDone && nativeObserved.isDone, "server tick did not deliver both backend replies")
            }.thenExecute {
                helper.assertTrue(deliveredOnServer && nativeDeliveredOnServer, "actor callback ran outside the server thread")
            }.thenWaitUntil {
                pumpNative()
                computer.serverTick(server.tickCount.toLong())
                helper.assertTrue(
                    computer.state == ProgramRuntimeState.WaitingForInput && nativeComputer.state == ProgramRuntimeState.WaitingForInput,
                    "both backend shells must become ready; native=${nativeComputer.state}",
                )
            }.thenExecute {
                terminal = computer.request(ProgramRuntimeActorCommand::TerminalFullState)
                nativeTerminal = nativeComputer.request(ProgramRuntimeActorCommand::TerminalFullState)
            }.thenWaitUntil {
                pumpNative()
                helper.assertTrue(terminal!!.isDone && nativeTerminal!!.isDone, "both terminal snapshots must be delivered")
            }.thenExecute {
                val snapshot = terminal!!.getNow(null).value as ProgramRuntimeActorValue.TerminalStateValue
                helper.assertTrue(
                    snapshot.state != null && (nativeTerminal!!.join().value as ProgramRuntimeActorValue.TerminalStateValue).state != null,
                    "actor boot did not publish a native terminal",
                )
            }.thenWaitUntil {
                pumpNative()
                val reason = nativeService.capacityFallbackReason()
                if (reason != null && reason != "calibration pending") helper.fail("Native calibration failed: $reason")
                helper.assertTrue(reason == null, "native calibration is pending")
                helper.assertTrue(
                    nativeService.runtimeMetrics().measuredCapacityWorkers == 1,
                    "native calibration did not use its selected pool",
                )
            }.thenExecute {
                closed = stores.stop(root)
                helper.assertTrue(drainCalls == 1 && !closed!!.isDone, "deferred close lost its pending barrier")
                helper.assertTrue(computer.state == ProgramRuntimeState.WaitingForInput, "deferred admission closed a live carrier")
            }.thenWaitUntil {
                pumpNative()
                stores.retryCloses(root)
                helper.assertTrue(drainCalls == 2, "accepted drain was retried")
                helper.assertTrue(closed!!.isDone, "actor did not close on its worker")
            }.thenExecute {
                closed!!.getNow(null)
                // Reopening proves the real native store was closed after the worker released its machine.
                val reopened = stores.store(root)
                helper.assertTrue(
                    reopened.health() == ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth.ACTIVE,
                    "filesystem store did not reopen after the actor close barrier",
                )
                closed = stores.stop(root)
            }.thenWaitUntil {
                pumpNative()
                helper.assertTrue(closed!!.isDone, "reopened store did not close on the persistence worker")
            }.thenExecute {
                closed!!.getNow(null)
                nativeRegistry.stop(server)
            }.thenSucceed()
    }
}
