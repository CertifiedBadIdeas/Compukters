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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest

/** Builds the shared immutable request whose decoded packet can be reused for dispatch. */
internal fun prepareCommandRequest(
    request: VmHostRequest,
    programId: Long,
): ProgramAddonRequest? {
    if (request.capability != CommandBatchProgramHost.SCHEMA.identity || request.operation != 0) return null
    return ProgramAddonRequest(request.identity, request.capability, request.operation, request.arguments, programId)
}

/** Invalid packets cost one request; the host rejects them when dispatching. */
internal fun commandRequestCost(request: ProgramAddonRequest?): Int {
    if (request == null) return 1
    return try {
        CommandBatchCodec.decode(request).size
    } catch (_: IllegalArgumentException) {
        1
    } catch (_: ClassCastException) {
        1
    }
}
