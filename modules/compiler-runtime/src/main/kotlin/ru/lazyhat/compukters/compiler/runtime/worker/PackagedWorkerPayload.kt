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

package ru.lazyhat.compukters.compiler.runtime.worker

import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import java.io.InputStream
import java.nio.file.Path

data class PackagedWorkerPayloadLimits(
    val entries: Int = 256,
    val bytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(entries > 0) { "packaged worker entry limit must be positive" }
        require(bytes > 0) { "packaged worker byte limit must be positive" }
    }
}

class PackagedWorkerPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object PackagedWorkerPayload {
    fun publish(
        archive: InputStream,
        cacheRoot: Path,
        limits: PackagedWorkerPayloadLimits = PackagedWorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        try {
            val published =
                ru.lazyhat.compukters.worker.payload.PackagedWorkerPayload.publish(
                    archive,
                    cacheRoot,
                    limits =
                        ru.lazyhat.compukters.worker.payload.PackagedWorkerPayloadLimits(
                            limits.entries,
                            limits.bytes,
                        ),
                )
            return WorkerPayloadLoader.load(published.root)
        } catch (exception: ru.lazyhat.compukters.worker.payload.PackagedWorkerPayloadException) {
            throw PackagedWorkerPayloadException(exception.message ?: "packaged worker publication failed", exception)
        } catch (exception: ru.lazyhat.compukters.worker.payload.WorkerPayloadException) {
            throw exception
        }
    }
}
