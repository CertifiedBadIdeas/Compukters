/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction

/** Reuse request/result assertions while exercising the persistent observation subscription. */
internal abstract class RecordingActorObserver : ProgramRuntimeActorObserver {
    override fun addonAction(action: ProgramAddonAction) = Unit

    abstract fun complete(
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
    )

    private var observed = false

    override fun updated(observation: ProgramRuntimeActorObservation) {
        observed = true
        complete(
            ProgramRuntimeActorReply(
                ProgramRuntimeRequestId(1),
                observation.state,
                ProgramRuntimeActorValue.None,
                observation.fileSystemGeneration,
                observation.retiredInstructions,
            ),
            null,
        )
    }

    override fun failed(failure: Throwable) {
        if (observed && failure is java.util.concurrent.CancellationException) return
        complete(null, failure)
    }
}

internal fun testObserver(completion: (ProgramRuntimeActorReply?, Throwable?) -> Unit): ProgramRuntimeActorObserver =
    object : RecordingActorObserver() {
        override fun complete(
            reply: ProgramRuntimeActorReply?,
            failure: Throwable?,
        ) = completion(reply, failure)
    }
