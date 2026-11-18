/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult

/** Owner-thread subscription for one carrier lifecycle, independent of frame admission. */
internal interface ProgramRuntimeActorObserver {
    fun addonAction(action: ProgramAddonAction)

    fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult? = null

    fun updated(observation: ProgramRuntimeActorObservation)

    fun failed(failure: Throwable)
}

/** Cumulative retirement observation within one native tick; carries no execution permit. */
data class ProgramRuntimeActorObservation(
    val worldTick: Long,
    val retiredInstructions: Long,
    val state: ProgramRuntimeState,
    val fileSystemGeneration: Long?,
    val needsFrame: Boolean = true,
)
