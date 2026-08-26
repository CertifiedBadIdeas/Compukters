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

package ru.lazyhat.compukters.ide.project

data class ProjectLimits(
    val manifestBytes: Int = 64 * 1024,
    val modules: Int = 128,
    val projectNameCodePoints: Int = 64,
    val projectNameUtf8Bytes: Int = 128,
) {
    init {
        require(manifestBytes >= 0) { "manifest byte limit must be non-negative" }
        require(modules >= 0) { "module count limit must be non-negative" }
        require(projectNameCodePoints >= 0) { "project name code-point limit must be non-negative" }
        require(projectNameUtf8Bytes >= 0) { "project name byte limit must be non-negative" }
    }
}
