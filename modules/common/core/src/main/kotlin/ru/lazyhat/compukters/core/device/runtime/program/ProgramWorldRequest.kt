/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity

/** Decoded world work, delivered on the carrier owner thread without settling execution credit. */
internal sealed interface ProgramWorldRequest {
    val identities: List<VmHostRequestIdentity>
    val programId: Long

    data class Redstone(
        override val identities: List<VmHostRequestIdentity>,
        override val programId: Long,
        val packed: Int,
        val changed: Boolean = true,
    ) : ProgramWorldRequest

    data class Sound(
        override val identities: List<VmHostRequestIdentity>,
        override val programId: Long,
        val requests: List<SoundRequest>,
    ) : ProgramWorldRequest

    data class Rejected(
        override val identities: List<VmHostRequestIdentity>,
        override val programId: Long,
        val response: HostResponse.Failure,
    ) : ProgramWorldRequest
}

/** Only a successful physical mutation changes the host's observed output register. */
internal data class ProgramWorldResult(
    val confirmedRedstoneOutput: Int? = null,
)
