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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisExecutionQueueTest {
    @Test
    fun `reader can cancel active analysis while its executor is occupied`() {
        AnalysisExecutionQueue(maximumQueued = 2).use { queue ->
            val started = CountDownLatch(1)
            val cancelled = CompletableFuture<Unit>()
            assertTrue(
                queue.submit(
                    RequestId.of(1uL),
                    task = { token ->
                        started.countDown()
                        while (!token.isCancelled) Thread.onSpinWait()
                    },
                    onCancelled = { cancelled.complete(Unit) },
                ),
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(queue.cancel(RequestId.of(1uL)))

            cancelled.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `cancellation racing completion always acknowledges accepted cancellation`() {
        AnalysisExecutionQueue(maximumQueued = 2).use { queue ->
            repeat(20_000) { index ->
                val requestId = RequestId.of((index * 2 + 1).toULong())
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val drained = CompletableFuture<Unit>()
                val acknowledged = AtomicBoolean()
                assertTrue(
                    queue.submit(
                        requestId,
                        task = {
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        },
                        onCancelled = { acknowledged.set(true) },
                    ),
                )
                assertTrue(queue.submit(RequestId.of((index * 2 + 2).toULong()), { drained.complete(Unit) }, {}))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                release.countDown()
                repeat(index % 500) { Thread.onSpinWait() }
                val accepted = queue.cancel(requestId)
                drained.get(5, TimeUnit.SECONDS)
                assertTrue(!accepted || acknowledged.get(), "accepted cancellation lost its acknowledgement at iteration $index")
            }
        }
    }

    @Test
    fun `queue rejects work beyond its fixed active plus queued bound`() {
        AnalysisExecutionQueue(maximumQueued = 2).use { queue ->
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            assertTrue(
                queue.submit(RequestId.of(1uL), {
                    started.countDown()
                    release.await()
                }, {}),
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(queue.submit(RequestId.of(2uL), {}, {}))
            assertTrue(queue.submit(RequestId.of(3uL), {}, {}))

            assertFalse(queue.submit(RequestId.of(4uL), {}, {}))
            release.countDown()
        }
    }

    @Test
    fun `failing response callback cannot strand the next queued request`() {
        AnalysisExecutionQueue(maximumQueued = 1).use { queue ->
            val release = CountDownLatch(1)
            val next = CompletableFuture<Unit>()
            assertTrue(
                queue.submit(
                    RequestId.of(1uL),
                    task = {
                        release.await()
                        error("analysis failed")
                    },
                    onCancelled = {},
                    onFailure = { error("output failed") },
                ),
            )
            assertTrue(queue.submit(RequestId.of(2uL), { next.complete(Unit) }, {}))

            release.countDown()

            next.get(5, TimeUnit.SECONDS)
        }
    }
}
