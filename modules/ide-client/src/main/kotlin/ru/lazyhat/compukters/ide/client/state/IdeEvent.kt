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

package ru.lazyhat.compukters.ide.client.state

import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.Collections

sealed interface IdeEvent {
    data class CatalogLoaded(
        val generation: Long,
        val projects: List<IdeProjectSummary>,
    ) : IdeEvent,
        ReplaceableIdeEvent {
        override val replacementKey: IdeEventReplacementKey = IdeEventReplacementKey.Catalog(generation)
    }

    data class PollCompleted(
        val generation: Long,
        val tree: ProjectTree,
    ) : IdeEvent,
        ReplaceableIdeEvent {
        override val replacementKey: IdeEventReplacementKey = IdeEventReplacementKey.Poll(generation)
    }

    data class BuildCompleted(
        val generation: Long,
        val result: IdeBuildSummary,
    ) : IdeEvent

    data class Failed(
        val generation: Long,
        val operation: IdeBusyOperation,
        val problem: IdeProblem,
    ) : IdeEvent
}

sealed interface IdeEventReplacementKey {
    data class Catalog(
        val generation: Long,
    ) : IdeEventReplacementKey

    data class Poll(
        val generation: Long,
    ) : IdeEventReplacementKey
}

interface ReplaceableIdeEvent {
    val replacementKey: IdeEventReplacementKey
}

internal fun IdeEvent.copyForQueue(): IdeEvent =
    when (this) {
        is IdeEvent.CatalogLoaded -> copy(projects = Collections.unmodifiableList(projects.toList()))

        is IdeEvent.PollCompleted,
        is IdeEvent.BuildCompleted,
        is IdeEvent.Failed,
        -> this
    }
