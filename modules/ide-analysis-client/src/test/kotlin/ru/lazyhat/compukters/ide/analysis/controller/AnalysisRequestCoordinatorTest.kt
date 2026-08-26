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
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertEquals(listOf(first, second), client.opens)
        scheduler.advanceBy(30)

        val presentation = assertIs<AnalysisQuery.Presentation>(client.queries.single())
        assertEquals(second.identity, presentation.identity)
    }

    @Test
    fun `automatic completion is replaced while manual completion dispatches immediately`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 30, 10)
        val snapshot = admittedSnapshot("val answer = 42")
        coordinator.sourceChanged(snapshot)

        coordinator.automaticCompletion(testPath(), 3)
        coordinator.automaticCompletion(testPath(), 7)
        scheduler.advanceBy(9)
        assertEquals(emptyList(), client.queries)
        scheduler.advanceBy(1)
        val automatic = assertIs<AnalysisQuery.Completion>(client.queries.single())
        assertEquals(7, automatic.offsetUtf16)
        assertEquals(CompletionTrigger.Automatic, automatic.trigger)

        coordinator.automaticCompletion(testPath(), 8)
        assertEquals(1, client.cancelled.size)
        coordinator.manualCompletion(testPath(), 9)
        val manual = assertIs<AnalysisQuery.Completion>(client.queries.last())
        assertEquals(9, manual.offsetUtf16)
        assertEquals(CompletionTrigger.Manual, manual.trigger)
        scheduler.advanceBy(10)
        assertEquals(2, client.queries.size)
    }
}
