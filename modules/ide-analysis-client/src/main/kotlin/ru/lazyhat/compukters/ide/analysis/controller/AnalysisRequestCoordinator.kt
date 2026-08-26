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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import java.util.concurrent.CompletableFuture

interface AnalysisRequestCoordinator : AutoCloseable {
    fun sourceChanged(snapshot: AdmittedAnalysisSnapshot)

    fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    )

    fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult>
}

class DefaultAnalysisRequestCoordinator(
    private val client: AnalysisClient,
    private val scheduler: AnalysisTaskScheduler,
    private val presentationDebounceNanos: Long,
    private val automaticCompletionDebounceNanos: Long,
) : AnalysisRequestCoordinator {
    private val lock = Any()
    private var snapshot: AdmittedAnalysisSnapshot? = null
    private var presentationTask: AnalysisScheduledTask? = null
    private var presentationFuture: CompletableFuture<AnalysisClientResult>? = null
    private var completionTask: AnalysisScheduledTask? = null
    private var completionFuture: CompletableFuture<AnalysisClientResult>? = null
    private var closed = false

    init {
        require(presentationDebounceNanos >= 0) { "presentation debounce must not be negative" }
        require(automaticCompletionDebounceNanos >= 0) { "automatic-completion debounce must not be negative" }
    }

    override fun sourceChanged(snapshot: AdmittedAnalysisSnapshot) {
        val oldPresentation: CompletableFuture<AnalysisClientResult>?
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            this.snapshot = snapshot
            presentationTask?.cancel()
            completionTask?.cancel()
            oldPresentation = presentationFuture
            oldCompletion = completionFuture
            presentationFuture = null
            completionFuture = null
            completionTask = null
            presentationTask =
                scheduler.schedule(presentationDebounceNanos) {
                    dispatchPresentation(snapshot)
                }
        }
        oldPresentation?.let(client::cancel)
        oldCompletion?.let(client::cancel)
        client.open(snapshot)
    }

    override fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        val current: AdmittedAnalysisSnapshot
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            current = snapshot ?: return
            completionTask?.cancel()
            oldCompletion = completionFuture
            completionFuture = null
            val query = AnalysisQuery.Completion(current.identity, path, offsetUtf16, CompletionTrigger.Automatic)
            completionTask = scheduler.schedule(automaticCompletionDebounceNanos) { dispatchCompletion(current, query) }
        }
        oldCompletion?.let(client::cancel)
    }

    override fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val current: AdmittedAnalysisSnapshot
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            current = checkNotNull(snapshot) { "analysis snapshot is not open" }
            completionTask?.cancel()
            completionTask = null
            oldCompletion = completionFuture
            completionFuture = null
        }
        oldCompletion?.let(client::cancel)
        return client.query(AnalysisQuery.Completion(current.identity, path, offsetUtf16, CompletionTrigger.Manual))
    }

    override fun close() {
        val futures: List<CompletableFuture<AnalysisClientResult>>
        synchronized(lock) {
            if (closed) return
            closed = true
            presentationTask?.cancel()
            completionTask?.cancel()
            presentationTask = null
            completionTask = null
            futures = listOfNotNull(presentationFuture, completionFuture)
            presentationFuture = null
            completionFuture = null
            snapshot = null
        }
        futures.forEach(client::cancel)
    }

    private fun dispatchPresentation(expected: AdmittedAnalysisSnapshot) {
        synchronized(lock) {
            if (closed || snapshot !== expected) return
            presentationTask = null
            presentationFuture = client.query(AnalysisQuery.Presentation(expected.identity))
        }
    }

    private fun dispatchCompletion(
        expected: AdmittedAnalysisSnapshot,
        query: AnalysisQuery.Completion,
    ) {
        synchronized(lock) {
            if (closed || snapshot !== expected) return
            completionTask = null
            completionFuture = client.query(query)
        }
    }
}
