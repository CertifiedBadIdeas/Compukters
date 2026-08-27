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

fun interface AnalysisResultSink {
    fun publish(result: AnalysisClientResult)
}

class DefaultAnalysisRequestCoordinator(
    private val client: AnalysisClient,
    private val scheduler: AnalysisTaskScheduler,
    private val presentationDebounceNanos: Long,
    private val automaticCompletionDebounceNanos: Long,
    private val resultSink: AnalysisResultSink = AnalysisResultSink {},
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
        val future: CompletableFuture<AnalysisClientResult>
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            current = checkNotNull(snapshot) { "analysis snapshot is not open" }
            completionTask?.cancel()
            completionTask = null
            oldCompletion = completionFuture
            future = client.query(AnalysisQuery.Completion(current.identity, path, offsetUtf16, CompletionTrigger.Manual))
            completionFuture = future
        }
        oldCompletion?.let(client::cancel)
        publishCompletion(current, future)
        return future
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
        val future =
            synchronized(lock) {
                if (closed || snapshot !== expected) return
                presentationTask = null
                client.query(AnalysisQuery.Presentation(expected.identity)).also { presentationFuture = it }
            }
        future.whenComplete { result, failure ->
            if (failure == null && result != null && admitPresentation(expected, future)) resultSink.publish(result)
        }
    }

    private fun dispatchCompletion(
        expected: AdmittedAnalysisSnapshot,
        query: AnalysisQuery.Completion,
    ) {
        val future =
            synchronized(lock) {
                if (closed || snapshot !== expected) return
                completionTask = null
                client.query(query).also { completionFuture = it }
            }
        publishCompletion(expected, future)
    }

    private fun publishCompletion(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ) {
        future.whenComplete { result, failure ->
            if (failure == null && result != null && admitCompletion(expected, future)) resultSink.publish(result)
        }
    }

    private fun admitPresentation(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ): Boolean =
        synchronized(lock) {
            if (closed || snapshot !== expected || presentationFuture !== future) return@synchronized false
            presentationFuture = null
            true
        }

    private fun admitCompletion(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ): Boolean =
        synchronized(lock) {
            if (closed || snapshot !== expected || completionFuture !== future) return@synchronized false
            completionFuture = null
            true
        }
}
