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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmStoreBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.util.concurrent.CompletableFuture

/** Storage/bootstrap commands only: this worker has no Guest advance or execution permit entry point. */
internal class ProgramRuntimeBootstrapProcessor(
    private val host: ProgramRuntimeHost,
) : AutoCloseable {
    val closed = CompletableFuture<Long?>()
    private var lastFileSystemGeneration: Long? = null
    private val hibernation =
        java.util.concurrent.atomic
            .AtomicReference<ProgramActorHibernation?>()

    fun prepareHibernation(request: ProgramActorHibernation) {
        check(hibernation.compareAndSet(null, request)) { "hibernation already requested" }
    }

    fun process(command: ProgramRuntimeActorCommand): ProgramRuntimeActorReply = reply(command.requestId) { execute(command) }

    private fun reply(
        requestId: ProgramRuntimeRequestId,
        operation: () -> ProgramRuntimeActorValue,
    ): ProgramRuntimeActorReply {
        val value =
            try {
                operation().also { captureGeneration() }
            } catch (_: VmVerificationException) {
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.Verification)
            } catch (failure: IllegalArgumentException) {
                ProgramRuntimeActorValue.Rejected(
                    ProgramRuntimeActorFailure.InvalidRequest(failure.message ?: "invalid runtime request"),
                )
            } catch (_: VmStoreBusyException) {
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.StoreBusy)
            } catch (failure: VmBridgeException) {
                ProgramRuntimeActorValue.Rejected(
                    ProgramRuntimeActorFailure.Bridge(failure.message ?: "native VM bridge failure"),
                )
            }
        return ProgramRuntimeActorReply(
            requestId,
            host.state,
            value,
            lastFileSystemGeneration,
        )
    }

    private fun execute(command: ProgramRuntimeActorCommand): ProgramRuntimeActorValue {
        return when (command) {
            is ProgramRuntimeActorCommand.Start -> {
                captureGeneration()
                ProgramRuntimeActorValue.Start(host.start(command.artifactBytes()))
            }

            is ProgramRuntimeActorCommand.RestoreOrBoot -> {
                val checkpoint =
                    try {
                        host.prepareRestoreBoot(command.worldTick)
                    } catch (failure: VmStoreBusyException) {
                        throw failure
                    } catch (failure: Exception) {
                        LOGGER.warn(failure) { "Computer checkpoint restoration failed; starting fresh execution" }
                        return coldBootAfterRestorationFailure(failure.message ?: "Checkpoint restoration failed")
                    }
                if (checkpoint == null) {
                    if (command.required) {
                        LOGGER.warn { "Required computer checkpoint is missing; starting fresh execution" }
                    }
                    ProgramRuntimeActorValue.Start(host.startBoot())
                } else {
                    ProgramRuntimeActorValue.RestorationPrepared(
                        checkpoint.programScopes,
                        checkpoint.addonState,
                        checkpoint.confirmedRedstoneOutput,
                    )
                }
            }

            is ProgramRuntimeActorCommand.CompleteRestoration -> {
                try {
                    host.completeRestoration(command.worldTick)
                    ProgramRuntimeActorValue.Start(ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult.Started)
                } catch (failure: VmStoreBusyException) {
                    throw failure
                } catch (failure: Exception) {
                    LOGGER.warn(failure) { "Computer checkpoint consumption failed; starting fresh execution" }
                    // Rebound addon resources belong to the owner thread; release them before requesting a fresh boot.
                    ProgramRuntimeActorValue.Start(host.abortRestoration(failure.message ?: "Checkpoint consumption failed"))
                }
            }

            is ProgramRuntimeActorCommand.AbortRestoration -> {
                coldBootAfterRestorationFailure(command.detail)
            }

            is ProgramRuntimeActorCommand.StartBoot -> {
                captureGeneration()
                ProgramRuntimeActorValue.Start(host.startBoot())
            }

            is ProgramRuntimeActorCommand.TerminalFullState -> {
                ProgramRuntimeActorValue.TerminalStateValue(host.terminalFullState()?.immutableCopy())
            }

            is ProgramRuntimeActorCommand.TerminalChangesSince -> {
                ProgramRuntimeActorValue.TerminalUpdateValue(host.terminalChangesSince(command.revision)?.immutableCopy())
            }

            is ProgramRuntimeActorCommand.FileSystemGeneration -> {
                ProgramRuntimeActorValue.FileSystemGeneration(host.filesystemGeneration())
            }

            is ProgramRuntimeActorCommand.ResourceSnapshot -> {
                ProgramRuntimeActorValue.ResourceSnapshotValue(host.resourceSnapshot())
            }

            is ProgramRuntimeActorCommand.Shutdown -> {
                captureGeneration()
                host.shutdown()
                host.discardCheckpoint()
                ProgramRuntimeActorValue.None
            }

            is ProgramRuntimeActorCommand.Reboot -> {
                captureGeneration()
                host.shutdown()
                host.discardCheckpoint()
                ProgramRuntimeActorValue.Start(host.startBoot(command.installSystem))
            }

            else -> {
                throw IllegalArgumentException("bootstrap does not support this command")
            }
        }
    }

    private fun coldBootAfterRestorationFailure(detail: String): ProgramRuntimeActorValue {
        LOGGER.warn { "Computer restoration abandoned: $detail; starting fresh execution" }
        host.abortRestoration(detail)
        host.discardCheckpoint()
        return ProgramRuntimeActorValue.Start(host.startBoot())
    }

    override fun close() {
        try {
            val generation =
                try {
                    try {
                        hibernation.get()?.let { request ->
                            request.effects.forEach { effect ->
                                when (effect) {
                                    is ProgramRuntimeActorEffect.CompleteAddons -> {
                                        effect.completions.forEach { host.completeAddon(it) }
                                    }

                                    is ProgramRuntimeActorEffect.CompleteRedstoneOutput,
                                    is ProgramRuntimeActorEffect.CompleteSound,
                                    -> {
                                        Unit
                                    }

                                    // A bootstrap host has never published a world request.

                                    is ProgramRuntimeActorEffect.RedstoneInput -> {
                                        error("invalid final world effect")
                                    }
                                }
                            }
                            if (request.saveExecution) {
                                host.settleExternalRequestsForHibernation()
                                host.hibernate(request.worldTick, request.addonState)
                            } else {
                                host.discardCheckpoint()
                            }
                        }
                    } catch (failure: Exception) {
                        LOGGER.warn(failure) { "Computer hibernation failed; closing without saved execution" }
                        host.discardCheckpoint()
                    }
                    captureGeneration()
                    lastFileSystemGeneration
                } finally {
                    host.close()
                }
            closed.complete(generation)
        } catch (failure: Throwable) {
            closed.completeExceptionally(failure)
            throw failure
        }
    }

    private fun captureGeneration() {
        (host.filesystemGeneration() ?: host.lastClosedFileSystemGeneration)?.let { lastFileSystemGeneration = it }
    }

    private fun TerminalState.immutableCopy(): TerminalState =
        copy(
            cells = cells.toList(),
            historyCells = historyCells.toList(),
            cursor = cursor.copy(),
        )

    private fun TerminalUpdate.immutableCopy(): TerminalUpdate =
        when (this) {
            is TerminalUpdate.Unchanged -> {
                copy()
            }

            is TerminalUpdate.Full -> {
                copy(state = state.immutableCopy())
            }

            is TerminalUpdate.Delta -> {
                copy(
                    changes =
                        changes.map { change ->
                            when (change) {
                                is TerminalChange.Patch -> change.copy(cells = change.cells.toList())
                                is TerminalChange.Fill -> change.copy()
                                is TerminalChange.Scroll -> change.copy()
                                is TerminalChange.Cursor -> change.copy(position = change.position.copy())
                                is TerminalChange.HistoryAppend -> change.copy(cells = change.cells.toList())
                                is TerminalChange.HistoryReplace -> change.copy(cells = change.cells.toList())
                                TerminalChange.Reset -> TerminalChange.Reset
                            }
                        },
                )
            }
        }
}
