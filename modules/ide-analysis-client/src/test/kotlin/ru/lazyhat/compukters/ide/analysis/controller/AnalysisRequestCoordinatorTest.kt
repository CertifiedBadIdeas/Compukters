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

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalysisRequestCoordinatorTest {
    @Test
    fun `source changes coalesce presentation for the latest immutable snapshot`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 30, 10)
        val first = admittedSnapshot("val first = 1")
        val second = admittedSnapshot("val second = 2")

        coordinator.sourceChanged(first)
        coordinator.sourceChanged(second)
        assertEquals(emptyList(), client.opens)
        assertEquals(emptyList(), client.queries)
        scheduler.advanceBy(30)

        val presentation = assertIs<AnalysisQuery.Presentation>(client.queries.single())
        assertEquals(second.identity, presentation.identity)
        assertSame(second, client.querySnapshots.single())
    }

    @Test
    fun `automatic completion waits for its debounce and uses the latest snapshot`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 150, 75)
        val first = admittedSnapshot("val answer = 4")
        val latest = admittedSnapshot("val answer = 42")

        coordinator.sourceChanged(first)
        coordinator.automaticCompletion(testPath(), 3)
        coordinator.sourceChanged(latest)
        coordinator.automaticCompletion(testPath(), 7)
        scheduler.advanceBy(74)
        assertEquals(emptyList(), client.queries)
        scheduler.advanceBy(1)

        val automatic = assertIs<AnalysisQuery.Completion>(client.queries.single())
        assertEquals(7, automatic.offsetUtf16)
        assertEquals(CompletionTrigger.Automatic, automatic.trigger)
        assertSame(latest, client.querySnapshots.single())
    }

    @Test
    fun `manual completion dispatches immediately and preempts active lower-priority work`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 0, 10)
        val snapshot = admittedSnapshot("val answer = 42")
        coordinator.sourceChanged(snapshot)
        scheduler.advanceBy(0)
        val presentation = client.queryFutures.single()

        val firstManual = coordinator.manualCompletion(testPath(), 3)
        assertEquals(listOf(presentation), client.cancelled)
        assertEquals(CompletionTrigger.Manual, assertIs<AnalysisQuery.Completion>(client.queries.last()).trigger)

        coordinator.automaticCompletion(testPath(), 8)
        scheduler.advanceBy(10)
        assertEquals(2, client.queries.size)
        assertEquals(listOf(presentation), client.cancelled)

        val secondManual = coordinator.manualCompletion(testPath(), 9)
        assertEquals(listOf(presentation, firstManual), client.cancelled)
        assertSame(secondManual, client.queryFutures.last())
    }

    @Test
    fun `automatic completion preempts presentation while presentation never preempts completion`() {
        val snapshot = admittedSnapshot("val answer = 42")
        val firstScheduler = ManualAnalysisTaskScheduler()
        val firstClient = RecordingAnalysisClient()
        val firstCoordinator = DefaultAnalysisRequestCoordinator(firstClient, firstScheduler, 30, 10)

        firstCoordinator.sourceChanged(snapshot)
        firstScheduler.advanceBy(30)
        val presentation = firstClient.queryFutures.single()
        firstCoordinator.automaticCompletion(testPath(), 3)
        assertEquals(listOf(presentation), firstClient.cancelled)

        val secondScheduler = ManualAnalysisTaskScheduler()
        val secondClient = RecordingAnalysisClient()
        val secondCoordinator = DefaultAnalysisRequestCoordinator(secondClient, secondScheduler, 30, 10)

        secondCoordinator.sourceChanged(snapshot)
        secondCoordinator.automaticCompletion(testPath(), 4)
        secondScheduler.advanceBy(10)
        val completion = secondClient.queryFutures.single()
        secondScheduler.advanceBy(20)
        assertEquals(2, secondClient.queries.size)
        assertTrue(completion !in secondClient.cancelled)
    }

    @Test
    fun `presentation and automatic completion publish only current results`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 0, 0, AnalysisResultSink(published::add))
        val first = admittedSnapshot("val first = 1")
        val second = admittedSnapshot("val second = 2")

        coordinator.sourceChanged(first)
        scheduler.advanceBy(0)
        val stalePresentation = client.queryFutures.single()
        coordinator.sourceChanged(second)
        stalePresentation.complete(AnalysisClientResult.Stale)
        assertEquals(emptyList(), published)

        scheduler.advanceBy(0)
        val presentation: AnalysisClientResult =
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    second.identity,
                    SnapshotPresentation.create(second.identity, mapOf(testPath() to "val second = 2".length)),
                ),
            )
        client.queryFutures.last().complete(presentation)
        assertEquals(listOf(presentation), published)

        coordinator.automaticCompletion(testPath(), 3)
        scheduler.advanceBy(0)
        client.queryFutures.last().complete(AnalysisClientResult.Stale)
        assertEquals(listOf(presentation, AnalysisClientResult.Stale), published)
    }

    @Test
    fun `late result is rejected when a different snapshot object reuses the identity`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 0, 0, AnalysisResultSink(published::add))
        val first = admittedSnapshot("val answer = 42")
        val replacement = AdmittedAnalysisSnapshot(first.identity, first.sources, first.profile, first.limits)

        coordinator.sourceChanged(first)
        scheduler.advanceBy(0)
        val stale = client.queryFutures.single()
        coordinator.sourceChanged(replacement)
        stale.complete(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    first.identity,
                    SnapshotPresentation.create(first.identity, mapOf(testPath() to "val answer = 42".length)),
                ),
            ),
        )

        assertEquals(emptyList(), published)
    }

    @Test
    fun `manual completion preempts automatic work and close suppresses late publication`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 30, 10, AnalysisResultSink(published::add))
        coordinator.sourceChanged(admittedSnapshot("val answer = 42"))
        coordinator.automaticCompletion(testPath(), 3)
        scheduler.advanceBy(10)
        val automatic = client.queryFutures.single()

        val manual = coordinator.manualCompletion(testPath(), 4)
        assertEquals(listOf(automatic), client.cancelled)
        automatic.complete(AnalysisClientResult.Cancelled)
        assertEquals(emptyList(), published)

        coordinator.close()
        manual.complete(AnalysisClientResult.Stale)
        assertEquals(emptyList(), published)
    }
}
