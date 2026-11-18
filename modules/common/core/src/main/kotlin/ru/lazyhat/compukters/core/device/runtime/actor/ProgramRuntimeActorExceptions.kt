/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

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
