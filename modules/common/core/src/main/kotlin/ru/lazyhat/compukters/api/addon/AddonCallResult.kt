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

package ru.lazyhat.compukters.api.addon

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

sealed interface AddonPollResult<out T> {
    data class Completed<T>(
        val value: T,
    ) : AddonPollResult<T>

    data class Failed(
        val kind: HostFailureKind,
        val code: Long,
    ) : AddonPollResult<Nothing>
}

sealed interface AddonCallResult<out T> {
    data class Completed<T>(
        val value: T,
    ) : AddonCallResult<T>

    data class Failed(
        val kind: HostFailureKind,
        val code: Long,
    ) : AddonCallResult<Nothing>

    class Pending<T>(
        val poll: () -> AddonPollResult<T>?,
    ) : AddonCallResult<T>
}

interface AddonHostHandler : AutoCloseable {
    fun reset() = Unit

    override fun close() = reset()
}

fun <T> addonCompleted(value: T): AddonCallResult<T> = AddonCallResult.Completed(value)

fun addonFailed(
    kind: HostFailureKind,
    code: Long,
): AddonCallResult<Nothing> = AddonCallResult.Failed(kind, code)

fun <T> addonPending(poll: () -> AddonPollResult<T>?): AddonCallResult<T> = AddonCallResult.Pending(poll)

fun <T> addonPollCompleted(value: T): AddonPollResult<T> = AddonPollResult.Completed(value)

fun addonPollFailed(
    kind: HostFailureKind,
    code: Long,
): AddonPollResult<Nothing> = AddonPollResult.Failed(kind, code)
