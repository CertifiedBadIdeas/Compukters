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
import ru.lazyhat.compukters.lang.runtime.vm.MAXIMUM_HOST_FAILURE_DETAIL_BYTES
import java.nio.charset.CharacterCodingException

sealed interface AddonPollResult<out T> {
    data class Completed<T>(
        val value: T,
    ) : AddonPollResult<T>

    data class Failed(
        val kind: HostFailureKind,
        val detail: String,
    ) : AddonPollResult<Nothing> {
        init {
            requireValidFailureDetail(detail)
        }
    }
}

sealed interface AddonCallResult<out T> {
    data class Completed<T>(
        val value: T,
    ) : AddonCallResult<T>

    data class Failed(
        val kind: HostFailureKind,
        val detail: String,
    ) : AddonCallResult<Nothing> {
        init {
            requireValidFailureDetail(detail)
        }
    }

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
    detail: String,
): AddonCallResult<Nothing> = AddonCallResult.Failed(kind, detail)

fun <T> addonPending(poll: () -> AddonPollResult<T>?): AddonCallResult<T> = AddonCallResult.Pending(poll)

fun <T> addonPollCompleted(value: T): AddonPollResult<T> = AddonPollResult.Completed(value)

fun addonPollFailed(
    kind: HostFailureKind,
    detail: String,
): AddonPollResult<Nothing> = AddonPollResult.Failed(kind, detail)

private fun requireValidFailureDetail(detail: String) {
    require(detail.isNotEmpty()) { "addon failure detail must not be empty" }
    val encoded =
        try {
            detail.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("addon failure detail must be valid UTF-8", error)
        }
    require(encoded.size <= MAXIMUM_HOST_FAILURE_DETAIL_BYTES) {
        "addon failure detail exceeds $MAXIMUM_HOST_FAILURE_DETAIL_BYTES UTF-8 bytes"
    }
}
