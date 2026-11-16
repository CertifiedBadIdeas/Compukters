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

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.actor.ActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramActorCloseDeferredException
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramActorHibernation
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorEffect
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorLease
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorRequestException
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeHostCompletion
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeTurnObserver
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.program.EmptyProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Server-side carrier of an actor-owned machine. Only immutable observations and request futures leave the actor.
 * The service must be pumped on this carrier's owning thread.
 */
class ActorProgramComputer internal constructor(
    private val service: ActorRuntimeService,
    private val lease: ProgramRuntimeActorLease,
    private val redstone: RedstoneHostPort,
    private val sound: SoundHostPort = SoundHostPort { SoundCommitResult.Failed(HostFailureKind.UNAVAILABLE, "Sound is unavailable") },
    private val addon: ProgramAddonHost = EmptyProgramAddonHost,
    private val stateSink: (ProgramRuntimeState) -> Unit = {},
) {
    constructor(
        service: ProgramRuntimeService,
        lease: ProgramRuntimeActorLease,
        redstone: RedstoneHostPort,
        sound: SoundHostPort = SoundHostPort { SoundCommitResult.Failed(HostFailureKind.UNAVAILABLE, "Sound is unavailable") },
        addon: ProgramAddonHost = EmptyProgramAddonHost,
        stateSink: (ProgramRuntimeState) -> Unit = {},
    ) : this(service.carrierRuntime, lease, redstone, sound, addon, stateSink)

    private val owner = Thread.currentThread()
    private var lifecycle = 0L
    private var pendingOutput: PendingOutput? = null
    private var pendingSound: PendingSound? = null
    private val pendingRedstoneInputs = ArrayDeque<Int>()
    private val pendingHostCompletions = ArrayDeque<ProgramRuntimeHostCompletion>()
    private var pendingHostContinuation = false
    private val outstandingAddonRequests = mutableMapOf<VmHostRequestIdentity, Long>()
    private var closeResult: CompletableFuture<Long?>? = null
    private var pendingLifecycle: LifecycleStage? = null
    private var bootRequest: CompletableFuture<ProgramRuntimeActorReply>? = null
    private var lastAdvanceTick = -1L
    private var lastObservedServerTick = -1L
    private var hostCompletionTick: Long? = null

    var restoring: Boolean = false
        private set

    var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        private set

    var fileSystemGeneration: Long? = null
        private set

    fun turnOn(requiredCheckpoint: Boolean = false): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        bootRequest?.let { previous ->
            if (!previous.isDone || state == ProgramRuntimeState.Running ||
                state == ProgramRuntimeState.WaitingForInput || state == ProgramRuntimeState.WaitingForCompiler
            ) {
                return previous.copy()
            }
        }
        val prepared =
            lifecycleRequest { id ->
                ProgramRuntimeActorCommand.RestoreOrBoot(id, maxOf(0, lastObservedServerTick), requiredCheckpoint)
            }
        val version = lifecycle
        restoring = true
        val restored =
            prepared.thenCompose { reply ->
                checkOwner()
                val checkpoint =
                    reply.value as? ProgramRuntimeActorValue.RestorationPrepared
                        ?: return@thenCompose CompletableFuture.completedFuture(reply)
                if (version != lifecycle || closeResult != null) {
                    return@thenCompose CompletableFuture.failedFuture<ProgramRuntimeActorReply>(
                        IllegalStateException("restoration was cancelled"),
                    )
                }
                try {
                    addon.restoreCheckpoint(checkpoint.addonStateBytes(), checkpoint.programScopes)
                } catch (failure: Exception) {
                    LOGGER.warn(failure) { "Computer addon restoration failed; releasing restored resources before fresh boot" }
                    return@thenCompose coldBootAfterAddonRestorationFailure(failure.message ?: "Addon resource restoration failed", version)
                }
                observe(
                    retryLifecycleStage(version) { id ->
                        ProgramRuntimeActorCommand.CompleteRestoration(id, maxOf(0, lastObservedServerTick))
                    },
                    version,
                ).thenCompose { completed ->
                    checkOwner()
                    val result = (completed.value as? ProgramRuntimeActorValue.Start)?.result
                    if (result is ProgramStartResult.Rejected) {
                        coldBootAfterAddonRestorationFailure("Checkpoint consumption failed: ${result.failure}", version)
                    } else {
                        CompletableFuture.completedFuture(completed)
                    }
                }
            }
        restored.whenComplete { _, _ -> if (version == lifecycle) restoring = false }
        bootRequest = restored
        return restored.copy()
    }

    private fun coldBootAfterAddonRestorationFailure(
        detail: String,
        version: Long,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (version != lifecycle || closeResult != null) {
            return CompletableFuture.failedFuture(IllegalStateException("restoration was cancelled"))
        }
        try {
            addon.reset()
        } catch (failure: Exception) {
            LOGGER.warn(failure) { "Computer restored addon cleanup failed before fresh boot" }
        }
        return observe(retryLifecycleStage(version) { id -> ProgramRuntimeActorCommand.AbortRestoration(id, detail) }, version)
    }

    fun reboot(installSystem: Boolean = false): CompletableFuture<ProgramRuntimeActorReply> {
        val request = lifecycleRequest { id -> ProgramRuntimeActorCommand.Reboot(id, installSystem) }
        val version = lifecycle
        restoring = true
        request.whenComplete { _, _ -> if (version == lifecycle) restoring = false }
        bootRequest = request
        return request.copy()
    }

    fun shutdown(): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        bootRequest = null
        return lifecycleRequest(ProgramRuntimeActorCommand::Shutdown)
    }

    fun resourceSnapshot(): CompletableFuture<ProgramRuntimeActorReply> = request(ProgramRuntimeActorCommand::ResourceSnapshot)

    /** Terminal, filesystem, deployment and input operations retain their typed actor command/reply contract. */
    fun request(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand): CompletableFuture<ProgramRuntimeActorReply> =
        send { id ->
            val prepared = command(id)
            require(
                prepared !is ProgramRuntimeActorCommand.RestoreOrBoot &&
                    prepared !is ProgramRuntimeActorCommand.CompleteRestoration &&
                    prepared !is ProgramRuntimeActorCommand.AbortRestoration &&
                    prepared !is ProgramRuntimeActorCommand.Start &&
                    prepared !is ProgramRuntimeActorCommand.StartBoot &&
                    prepared !is ProgramRuntimeActorCommand.Reboot &&
                    prepared !is ProgramRuntimeActorCommand.Shutdown,
            ) { "use carrier lifecycle and tick operations for machine control" }
            prepared
        }

    fun serverTick(
        worldTick: Long,
        redstoneInput: Int? = null,
    ) {
        checkOwner()
        require(worldTick >= 0)
        redstoneInput?.let(::enqueueRedstoneInput)
        collectAddonCompletions()
        lastObservedServerTick = maxOf(lastObservedServerTick, worldTick)
        if (closeResult != null) return
        pendingLifecycle?.let { stage ->
            if (!stage.inFlight && worldTick > stage.lastAttemptTick) submitLifecycleStage(stage)
            return
        }
        if (restoring) return
        if (worldTick <= lastAdvanceTick ||
            (
                state != ProgramRuntimeState.Running &&
                    state != ProgramRuntimeState.WaitingForCompiler &&
                    pendingOutput == null &&
                    pendingSound == null &&
                    pendingHostCompletions.isEmpty() && !pendingHostContinuation &&
                    pendingRedstoneInputs.isEmpty()
            )
        ) {
            return
        }
        submitTurn(worldTick, continuation = false)
    }

    private fun submitTurn(
        worldTick: Long,
        continuation: Boolean,
    ) {
        if (closeResult != null) return
        val output = pendingOutput
        val requestedSound = pendingSound
        val addonCompletions =
            try {
                prepareHostCompletions()
            } catch (failure: Exception) {
                failUnlessBusy(failure)
                return
            }
        check(output == null || requestedSound == null) { "computer cannot own two pending world requests" }
        var input = pendingRedstoneInputs.firstOrNull()
        if (input != null) {
            val separate =
                try {
                    service.submitRedstoneInput(lease.endpoint, input)
                } catch (failure: Exception) {
                    failUnlessBusy(failure)
                    return
                }
            if (separate == false) return
            if (separate == true) {
                pendingRedstoneInputs.removeFirst()
                input = null
            }
        }
        val effects =
            buildList {
                output?.let {
                    add(
                        ProgramRuntimeActorEffect.CompleteRedstoneOutput(
                            it.requestId,
                            it.packed,
                            it.result,
                        ),
                    )
                }
                requestedSound?.let {
                    add(ProgramRuntimeActorEffect.CompleteSound(it.requestId, it.result))
                }
                if (addonCompletions.isNotEmpty()) add(ProgramRuntimeActorEffect.CompleteAddons(addonCompletions))
                input?.let { add(ProgramRuntimeActorEffect.RedstoneInput(it)) }
            }
        val currentLifecycle = lifecycle
        val completion =
            object : ProgramRuntimeTurnObserver {
                override fun addonAction(action: ProgramAddonAction) {
                    checkOwner()
                    acceptAddonAction(action, currentLifecycle)
                }

                override fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult? = acceptWorldRequest(request, currentLifecycle)

                override fun complete(
                    reply: ProgramRuntimeActorReply?,
                    failure: Throwable?,
                ) {
                    completeTurn(reply, failure, currentLifecycle, worldTick)
                }
            }
        if (continuation) {
            if (!service.continueTurn(lease.endpoint, worldTick, effects, completion)) return
        } else {
            lastAdvanceTick = worldTick
            val submission = service.submitTurn(lease.endpoint, worldTick, effects, completion)
            if (submission != VmActorSubmission.ACCEPTED) {
                failUnlessBusy(ProgramRuntimeActorRequestException(submission))
                return
            }
        }
        pendingOutput = null
        pendingSound = null
        pendingHostContinuation = false
        acknowledgeHostCompletions(addonCompletions.size)
        if (input != null) pendingRedstoneInputs.removeFirst()
        hostCompletionTick?.let { completedAt ->
            service.recordHostContinuationDelay((worldTick - completedAt).coerceAtLeast(0))
            hostCompletionTick = null
        }
    }

    private fun prepareHostCompletions(): List<ProgramAddonCompletion> {
        val completions = pendingHostCompletions.toList()
        val accepted =
            service.submitHostCompletions(lease.endpoint, completions) ?: return completions.map {
                val reply = it as ProgramRuntimeHostCompletion.Reply
                ProgramAddonCompletion(reply.identity, reply.response)
            }
        check(accepted in 0..completions.size)
        acknowledgeHostCompletions(accepted)
        if (accepted > 0) pendingHostContinuation = true
        return emptyList()
    }

    private fun acknowledgeHostCompletions(count: Int) {
        repeat(count) {
            val completion = pendingHostCompletions.removeFirst()
            if (completion is ProgramRuntimeHostCompletion.Reply) outstandingAddonRequests.remove(completion.identity)
        }
    }

    private fun completeTurn(
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
        version: Long,
        worldTick: Long,
    ) {
        checkOwner()
        if (version != lifecycle || closeResult != null) return
        if (failure != null) {
            failUnlessBusy(failure)
            return
        }
        try {
            val observed = requireNotNull(reply)
            fileSystemGeneration = observed.fileSystemGeneration
            publish(observed.state)
            if (version != lifecycle || closeResult != null) return
            acceptAdvanceReply(observed, version)
            if (version != lifecycle || closeResult != null) return
            if (pendingOutput != null || pendingSound != null || pendingHostCompletions.isNotEmpty() || pendingHostContinuation) {
                submitTurn(worldTick, continuation = true)
            }
        } catch (cause: Throwable) {
            if (version == lifecycle && closeResult == null) failUnlessBusy(cause)
        }
    }

    fun hibernateAsync(worldTick: Long): CompletableFuture<Long?> =
        tryHibernateAsync(worldTick) ?: CompletableFuture.failedFuture(ProgramActorCloseDeferredException())

    /** Null means no close was admitted; the owner may retry without losing resources or replies. */
    fun tryHibernateAsync(worldTick: Long): CompletableFuture<Long?>? {
        checkOwner()
        closeResult?.let { return it.copy() }
        require(worldTick >= 0)
        // Admission has run no Guest code; retain the original checkpoint and its resource descriptions.
        if (restoring) return tryHibernate(worldTick, byteArrayOf())
        val resources =
            try {
                collectAddonCompletions()
                addon.checkpoint().also { require(it.size <= 1024 * 1024) { "addon checkpoint is too large" } }
            } catch (failure: Exception) {
                LOGGER.warn(failure) { "Computer addon hibernation failed; execution will cold boot on reload" }
                return tryHibernate(worldTick, byteArrayOf(), saveExecution = false)
            }
        return tryHibernate(worldTick, resources)
    }

    /** Final acknowledged world results enter the worker before capture; undelivered requests are cancelled. */
    internal fun hibernateAsync(
        worldTick: Long,
        addonState: ByteArray,
        saveExecution: Boolean = true,
    ): CompletableFuture<Long?> =
        tryHibernate(worldTick, addonState, saveExecution) ?: CompletableFuture.failedFuture(ProgramActorCloseDeferredException())

    private fun tryHibernate(
        worldTick: Long,
        addonState: ByteArray,
        saveExecution: Boolean = true,
    ): CompletableFuture<Long?>? {
        checkOwner()
        require(worldTick >= 0)
        closeResult?.let { return it.copy() }
        val addonCompletions =
            try {
                prepareHostCompletions()
            } catch (failure: Exception) {
                return CompletableFuture.failedFuture(failure)
            }
        // Native replies can drain before another close attempt, without executing Guest instructions.
        if (addonCompletions.isEmpty() && pendingHostCompletions.isNotEmpty()) return null
        val effects =
            buildList {
                pendingOutput?.let { add(ProgramRuntimeActorEffect.CompleteRedstoneOutput(it.requestId, it.packed, it.result)) }
                pendingSound?.let { add(ProgramRuntimeActorEffect.CompleteSound(it.requestId, it.result)) }
                if (addonCompletions.isNotEmpty()) add(ProgramRuntimeActorEffect.CompleteAddons(addonCompletions))
            }
        val request = ProgramActorHibernation(worldTick, addonState, effects, saveExecution)
        return tryCloseWith(request)
    }

    /** The future completes after accepted work drains and native resources close, without requiring result pumping. */
    fun closeAsync(): CompletableFuture<Long?> {
        checkOwner()
        closeResult?.let { return it.copy() }
        return tryCloseWith(null) ?: CompletableFuture.failedFuture(ProgramActorCloseDeferredException())
    }

    private fun tryCloseWith(request: ProgramActorHibernation?): CompletableFuture<Long?>? {
        val reservation =
            try {
                lease.reserveClose(request)
            } catch (_: ProgramActorCloseDeferredException) {
                return null
            } catch (failure: Exception) {
                return CompletableFuture.failedFuture(failure)
            }
        val result = reservation.result()
        closeResult = result // Fence reentrant callbacks before committing backend cancellation.
        lifecycle++
        cancelLifecycleStage()
        restoring = false
        pendingOutput = null
        pendingSound = null
        pendingRedstoneInputs.clear()
        pendingHostCompletions.clear()
        pendingHostContinuation = false
        outstandingAddonRequests.clear()
        hostCompletionTick = null
        reservation.commit()
        try {
            addon.close()
        } catch (failure: Exception) {
            LOGGER.warn(failure) { "Computer addon cleanup failed after native close was scheduled" }
        }
        publish(ProgramRuntimeState.Closed)
        return result.copy()
    }

    private fun acceptAddonAction(
        action: ProgramAddonAction,
        currentLifecycle: Long,
    ) {
        if (currentLifecycle != lifecycle || closeResult != null) return
        when (action) {
            is ProgramAddonAction.Started -> {
                addon.programStarted(action.programId)
            }

            is ProgramAddonAction.Stopped -> {
                addon.programStopped(action.programId)
                if (currentLifecycle != lifecycle || closeResult != null) return
                val retired = outstandingAddonRequests.filterValues { it == action.programId }.keys
                pendingHostCompletions.removeAll { it is ProgramRuntimeHostCompletion.Reply && it.programId == action.programId }
                retired.forEach(outstandingAddonRequests::remove)
            }

            is ProgramAddonAction.Request -> {
                val addonRequest = action.request
                check(pendingHostCompletions.size < MAXIMUM_PENDING_HOST_COMPLETIONS) { "host completion queue is full" }
                check(
                    outstandingAddonRequests.put(addonRequest.identity, addonRequest.programId) == null,
                ) { "duplicate pending addon request" }
                val result =
                    try {
                        addon.dispatch(addonRequest)
                    } catch (_: Exception) {
                        ProgramAddonDispatch.Completed(HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, "Addon request failed"))
                    }
                if (currentLifecycle != lifecycle || closeResult != null) return
                if (result is ProgramAddonDispatch.Completed) {
                    pendingHostCompletions +=
                        ProgramRuntimeHostCompletion.Reply(addonRequest.identity, result.response, addonRequest.programId)
                }
                hostCompletionTick = lastObservedServerTick
            }
        }
    }

    private fun acceptWorldRequest(
        request: ProgramWorldRequest,
        version: Long,
    ): ProgramWorldResult? {
        if (version != lifecycle || closeResult != null) return null
        check(request.identities.isNotEmpty() && request.identities.distinct().size == request.identities.size)
        val allowance = request.identities.size + if (request is ProgramWorldRequest.Redstone && request.changed) 1 else 0
        check(allowance <= MAXIMUM_PENDING_HOST_COMPLETIONS - pendingHostCompletions.size) { "host completion queue is full" }
        val queued = pendingHostCompletions.filterIsInstance<ProgramRuntimeHostCompletion.Reply>().mapTo(mutableSetOf()) { it.identity }
        check(request.identities.none { it in queued }) { "duplicate pending world request" }
        var confirmed: Int? = null
        val responses: List<HostResponse> =
            when (request) {
                is ProgramWorldRequest.Redstone -> {
                    RedstoneWire.requireOutputRegister(request.packed)
                    val result = if (request.changed) commitRedstone(request.packed) else RedstoneCommitResult.Committed
                    if (version != lifecycle || closeResult != null) return null
                    val response =
                        when (result) {
                            RedstoneCommitResult.Committed -> {
                                if (request.changed) {
                                    pendingHostCompletions +=
                                        ProgramRuntimeHostCompletion.ConfirmRedstoneOutput(request.packed)
                                }
                                confirmed = request.packed
                                HostResponse.UnitSuccess
                            }

                            is RedstoneCommitResult.Failed -> {
                                HostResponse.Failure(result.kind, result.detail)
                            }

                            RedstoneCommitResult.Deferred -> {
                                error("world redstone completion cannot be deferred")
                            }
                        }
                    List(request.identities.size) { response }
                }

                is ProgramWorldRequest.Sound -> {
                    check(request.requests.size == request.identities.size)
                    val result = emitSound(request.requests)
                    if (version != lifecycle || closeResult != null) return null
                    when (result) {
                        is SoundCommitResult.Completed -> {
                            check(result.admissions.size == request.identities.size) { "sound completion size mismatch" }
                            result.admissions.map { HostResponse.BoolSuccess(it) }
                        }

                        is SoundCommitResult.Failed -> {
                            List(request.identities.size) { HostResponse.Failure(result.kind, result.detail) }
                        }

                        SoundCommitResult.Deferred -> {
                            error("world sound completion cannot be deferred")
                        }
                    }
                }

                is ProgramWorldRequest.Rejected -> {
                    List(request.identities.size) { request.response }
                }
            }
        request.identities.forEachIndexed { index, identity ->
            pendingHostCompletions += ProgramRuntimeHostCompletion.Reply(identity, responses[index], request.programId)
        }
        hostCompletionTick = lastObservedServerTick
        return ProgramWorldResult(confirmed)
    }

    private fun commitRedstone(packed: Int): RedstoneCommitResult =
        try {
            redstone.commitOutput(packed).also {
                check(it != RedstoneCommitResult.Deferred) { "server redstone port must complete the world mutation" }
            }
        } catch (_: Exception) {
            RedstoneCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, "Redstone output failed")
        }

    private fun emitSound(requests: List<SoundRequest>): SoundCommitResult =
        try {
            sound.emit(requests).also { check(it != SoundCommitResult.Deferred) { "server sound port must complete the world mutation" } }
        } catch (_: Exception) {
            SoundCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, "Sound output failed")
        }

    private fun acceptAdvanceReply(
        reply: ProgramRuntimeActorReply,
        currentLifecycle: Long,
    ) {
        reply.addonActions.forEach { acceptAddonAction(it, currentLifecycle) }
        if (currentLifecycle != lifecycle || closeResult != null) return
        when (val request = reply.value) {
            is ProgramRuntimeActorValue.RedstoneOutputRequested -> {
                val result = commitRedstone(request.packed)
                if (currentLifecycle != lifecycle || closeResult != null) return
                pendingOutput = PendingOutput(reply.requestId, request.packed, result)
                hostCompletionTick = lastObservedServerTick
            }

            is ProgramRuntimeActorValue.SoundRequested -> {
                val result = emitSound(request.requests)
                if (currentLifecycle != lifecycle || closeResult != null) return
                pendingSound = PendingSound(reply.requestId, result)
                hostCompletionTick = lastObservedServerTick
            }

            else -> {
                Unit
            }
        }
        if (
            reply.state != ProgramRuntimeState.Running &&
            reply.state != ProgramRuntimeState.WaitingForInput &&
            reply.state != ProgramRuntimeState.WaitingForCompiler
        ) {
            discardHostRequests()
        }
    }

    private fun lifecycleRequest(
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        lifecycle++
        cancelLifecycleStage()
        restoring = false
        pendingOutput = null
        pendingSound = null
        pendingRedstoneInputs.clear()
        discardHostRequests()
        hostCompletionTick = null
        return observe(retryLifecycleStage(lifecycle, command), lifecycle)
    }

    private fun retryLifecycleStage(
        version: Long,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        if (version != lifecycle || closeResult != null) {
            return CompletableFuture.failedFuture(CancellationException("computer lifecycle was replaced"))
        }
        check(pendingLifecycle == null) { "a lifecycle stage is already pending" }
        val stage = LifecycleStage(version, command)
        pendingLifecycle = stage
        submitLifecycleStage(stage)
        return stage.result
    }

    private fun submitLifecycleStage(stage: LifecycleStage) {
        checkOwner()
        stage.inFlight = true
        stage.lastAttemptTick = lastObservedServerTick
        try {
            if (pendingHostCompletions.isNotEmpty()) {
                check(prepareHostCompletions().isEmpty()) { "lifecycle retained obsolete Guest replies" }
                if (pendingHostCompletions.isNotEmpty()) {
                    stage.inFlight = false
                    return // Preserve physical-output acknowledgements until bounded native admission succeeds.
                }
            }
            pendingHostContinuation = false
        } catch (failure: Exception) {
            stage.inFlight = false
            pendingLifecycle = null
            stage.result.completeExceptionally(failure)
            return
        }
        service.request(lease.endpoint, stage.command).whenComplete { reply, failure ->
            checkOwner()
            if (pendingLifecycle !== stage || stage.version != lifecycle || closeResult != null) return@whenComplete
            stage.inFlight = false
            val cause = if (failure is CompletionException) failure.cause ?: failure else failure
            val admissionBusy = cause is ProgramRuntimeActorRequestException && cause.submission == VmActorSubmission.MAILBOX_FULL
            val storeBusy = (reply?.value as? ProgramRuntimeActorValue.Rejected)?.failure == ProgramRuntimeActorFailure.StoreBusy
            if (admissionBusy || storeBusy) return@whenComplete
            pendingLifecycle = null
            if (failure != null) stage.result.completeExceptionally(failure) else stage.result.complete(reply)
        }
    }

    private fun cancelLifecycleStage() {
        val previous = pendingLifecycle ?: return
        pendingLifecycle = null
        previous.result.completeExceptionally(CancellationException("computer lifecycle was replaced"))
    }

    private class LifecycleStage(
        val version: Long,
        val command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
        val result: CompletableFuture<ProgramRuntimeActorReply> = CompletableFuture(),
        var inFlight: Boolean = false,
        var lastAttemptTick: Long = -1,
    )

    private fun send(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        return observe(service.request(lease.endpoint, command), lifecycle)
    }

    private fun observe(
        future: CompletableFuture<ProgramRuntimeActorReply>,
        version: Long,
    ): CompletableFuture<ProgramRuntimeActorReply> =
        future.thenApply { reply ->
            checkOwner()
            if (version == lifecycle && closeResult == null) {
                fileSystemGeneration = reply.fileSystemGeneration
                publish(reply.state)
            }
            reply
        }

    private fun failUnlessBusy(failure: Throwable) {
        val cause = if (failure is CompletionException) failure.cause ?: failure else failure
        if (
            cause is ProgramRuntimeActorRequestException &&
            (cause.submission == VmActorSubmission.MAILBOX_FULL || cause.submission == VmActorSubmission.PERMIT_PENDING)
        ) {
            return
        }
        publish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(cause.message ?: "actor request failed")))
    }

    private fun enqueueRedstoneInput(latest: Int) {
        val checkedLatest = RedstoneWire.requireInputPacket(latest)
        if (pendingRedstoneInputs.size < service.redstoneInputCapacity) {
            pendingRedstoneInputs.addLast(checkedLatest)
            return
        }
        val pending = pendingRedstoneInputs.removeLast()
        val changed = RedstoneWire.inputChangedMask(checkedLatest) or RedstoneWire.inputChangedMask(pending)
        pendingRedstoneInputs.addLast((checkedLatest and RedstoneWire.ALL_SIDES_MASK.inv()) or changed)
        service.recordCoalescedRedstoneInput()
    }

    private fun collectAddonCompletions() {
        val available = MAXIMUM_ADDON_COMPLETIONS_PER_TICK - pendingHostCompletions.size
        if (available <= 0 || outstandingAddonRequests.isEmpty()) return
        val polled = addon.poll(available)
        require(polled.size <= available) { "addon host exceeded its completion budget" }
        val queued = pendingHostCompletions.filterIsInstance<ProgramRuntimeHostCompletion.Reply>().mapTo(mutableSetOf()) { it.identity }
        polled.forEach { completion ->
            require(completion.identity in outstandingAddonRequests) { "addon host completed an unknown or stale request" }
            require(queued.add(completion.identity)) { "addon host completed one request more than once" }
            pendingHostCompletions +=
                ProgramRuntimeHostCompletion.Reply(
                    completion.identity,
                    completion.response,
                    outstandingAddonRequests.getValue(completion.identity),
                )
        }
    }

    private fun discardHostRequests() {
        // Physical output already changed; retain its native acknowledgement even if Guest replies are obsolete.
        pendingHostCompletions.removeAll { it is ProgramRuntimeHostCompletion.Reply }
        pendingHostContinuation = pendingHostCompletions.isNotEmpty()
        outstandingAddonRequests.clear()
        addon.reset()
    }

    private fun publish(next: ProgramRuntimeState) {
        if (state == next) return
        state = next
        stateSink(next)
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "computer carrier must run on its owning server thread" }

    private data class PendingOutput(
        val requestId: ProgramRuntimeRequestId,
        val packed: Int,
        val result: RedstoneCommitResult,
    )

    private data class PendingSound(
        val requestId: ProgramRuntimeRequestId,
        val result: SoundCommitResult,
    )

    private companion object {
        const val MAXIMUM_ADDON_COMPLETIONS_PER_TICK = 256
        const val MAXIMUM_PENDING_HOST_COMPLETIONS = 512
    }
}
