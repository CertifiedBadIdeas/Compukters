/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.playground

import kotlinx.coroutines.CancellationException
import ru.lazyhat.compukters.lang.runtime.capability.CapabilityRegistry
import ru.lazyhat.compukters.lang.runtime.capability.TerminalCapability
import ru.lazyhat.compukters.lang.runtime.capability.TerminalLimits
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

fun interface PlaygroundExecutor {
    suspend fun execute(artifact: ByteArray): PlaygroundExecution
}

sealed interface PlaygroundExecution {
    data object Success : PlaygroundExecution

    data object VerificationFailure : PlaygroundExecution

    data class AdmissionFailure(
        val code: Int,
    ) : PlaygroundExecution

    data class StartFailure(
        val code: Int,
    ) : PlaygroundExecution

    data class Trap(
        val trap: GuestTrap,
    ) : PlaygroundExecution

    data class Fault(
        val fault: VmFault,
    ) : PlaygroundExecution

    data class HostFailure(
        val kind: HostFailureKind,
        val code: Long,
    ) : PlaygroundExecution

    data class Quota(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : PlaygroundExecution

    data class ResourceFailure(
        val collectionAttempted: Boolean,
    ) : PlaygroundExecution

    data class PlatformFailure(
        val detail: String,
    ) : PlaygroundExecution
}

class NativePlaygroundExecutor(
    private val library: Path,
    input: InputStream,
    output: OutputStream,
    terminalLimits: TerminalLimits = TerminalLimits(),
    private val maximumAdvances: Int = 1024,
) : PlaygroundExecutor {
    private val capabilities = CapabilityRegistry(listOf(TerminalCapability(input, output, terminalLimits)))
    private var loaded = false

    init {
        require(maximumAdvances > 0) { "maximum advances must be positive" }
    }

    override suspend fun execute(artifact: ByteArray): PlaygroundExecution =
        try {
            loadLibrary()
            VmSession.open(artifact).use { session ->
                repeat(maximumAdvances) {
                    when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET)) {
                        is VmOutcome.HostRequest -> {
                            session.resume(outcome.request.id, capabilities.dispatch(outcome.request))
                        }

                        is VmOutcome.Halted -> {
                            return PlaygroundExecution.Success
                        }

                        VmOutcome.SliceExhausted -> {
                            return@repeat
                        }

                        is VmOutcome.AllocationExhausted -> {
                            return PlaygroundExecution.ResourceFailure(outcome.collectionAttempted)
                        }

                        is VmOutcome.QuotaExhausted -> {
                            return PlaygroundExecution.Quota(outcome.kind, outcome.limit, outcome.consumed)
                        }

                        is VmOutcome.Crashed -> {
                            return PlaygroundExecution.Trap(outcome.trap)
                        }

                        is VmOutcome.Faulted -> {
                            return PlaygroundExecution.Fault(outcome.fault)
                        }

                        is VmOutcome.HostFailed -> {
                            return PlaygroundExecution.HostFailure(outcome.kind, outcome.code)
                        }
                    }
                }
            }
            PlaygroundExecution.PlatformFailure("maximum VM slice count exceeded")
        } catch (_: VmVerificationException) {
            PlaygroundExecution.VerificationFailure
        } catch (exception: VmAdmissionException) {
            PlaygroundExecution.AdmissionFailure(exception.code)
        } catch (exception: VmStartException) {
            PlaygroundExecution.StartFailure(exception.code)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            PlaygroundExecution.PlatformFailure(exception.message ?: exception::class.java.simpleName)
        }

    @Synchronized
    private fun loadLibrary() {
        if (loaded) return
        VmRuntime.loadNativeLibrary(library)
        loaded = true
    }

    private companion object {
        const val GUEST_BUDGET = 4096
        const val MAINTENANCE_BUDGET = 4096
    }
}
