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

import ru.lazyhat.compukters.ide.project.fs.ProjectPath

sealed interface IdeCommand {
    data class OpenProject(
        val directoryName: String,
    ) : IdeCommand

    data class OpenFile(
        val path: ProjectPath,
    ) : IdeCommand

    data class Edit(
        val input: IdeEditorInput,
    ) : IdeCommand

    data object Save : IdeCommand

    data object Resolve : IdeCommand

    data object Build : IdeCommand

    data object CancelBuild : IdeCommand

    data object ManualCompletion : IdeCommand

    data object CloseRequested : IdeCommand
}
