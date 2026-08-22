/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Path

interface WorkerProcess : AutoCloseable {
    val isAlive: Boolean

    fun writeFrame(frame: ByteArray)

    fun readFrame(deadlineNanos: Long): ByteArray?

    fun stderrSnapshot(): ByteArray

    fun terminate(graceMillis: Long)
}

fun interface WorkerProcessFactory {
    fun start(
        payload: PublishedWorkerPayload,
        launch: WorkerLaunch,
    ): WorkerProcess
}

data class WorkerLaunch(
    val javaExecutable: Path,
    val maximumHeapMiB: Int,
    val maximumMetaspaceMiB: Int,
    val temporaryDirectory: Path,
    val expectedIdentity: WorkerIdentity,
) {
    init {
        require(maximumHeapMiB > 0) { "maximum heap must be positive" }
        require(maximumMetaspaceMiB > 0) { "maximum metaspace must be positive" }
    }
}
