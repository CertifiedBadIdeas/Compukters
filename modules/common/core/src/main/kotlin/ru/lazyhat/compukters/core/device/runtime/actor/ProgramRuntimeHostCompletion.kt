/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity

/** Transport work retained only until the ordered native outbox accepts it. */
internal sealed interface ProgramRuntimeHostCompletion {
    data class Reply(
        val identity: VmHostRequestIdentity,
        val response: HostResponse,
        val programId: Long? = null,
    ) : ProgramRuntimeHostCompletion

    data class ConfirmRedstoneOutput(
        val packed: Int,
    ) : ProgramRuntimeHostCompletion
}
