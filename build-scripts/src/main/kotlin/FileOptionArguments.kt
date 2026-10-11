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

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.process.CommandLineArgumentProvider

class FileOption(
    @get:Input val option: String,
    @get:Internal val files: FileCollection,
) {
    @get:Input
    val path: String get() = files.singleFile.absolutePath
}

class FileOptionArguments(@get:Nested val options: List<FileOption>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = options.flatMap { listOf(it.option, it.path) }
}

fun Project.fileOptionArguments(vararg options: Pair<String, Any>): FileOptionArguments =
    FileOptionArguments(options.map { (option, value) -> FileOption(option, objects.fileCollection().from(value)) })
