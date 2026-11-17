/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult

/** Internal owner-delivered turn result. A rejected submission never invokes this callback. */
internal fun interface ProgramRuntimeTurnCompletion {
    fun complete(
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
    )
}

/** Owner-delivered actions may arrive before final credit settlement; they do not finish the turn. */
internal interface ProgramRuntimeTurnObserver : ProgramRuntimeTurnCompletion {
    fun addonAction(action: ProgramAddonAction)

    /** Null preserves external routing for observers that do not own world ports. */
    fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult? = null
}

open class ProgramRuntimeActorRequestException(
    val submission: VmActorSubmission,
) : IllegalStateException("runtime actor request was not accepted: $submission")

/** Close was not admitted; the carrier still owns its live resources and may retry on its owner. */
class ProgramActorCloseDeferredException : ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL)

class ProgramRuntimeActorFailedException(
    val endpoint: VmActorEndpoint,
    cause: Throwable,
) : IllegalStateException("runtime actor failed: $endpoint", cause)

class ProgramRuntimeActorServiceClosedException : IllegalStateException("runtime actor service is closed")
