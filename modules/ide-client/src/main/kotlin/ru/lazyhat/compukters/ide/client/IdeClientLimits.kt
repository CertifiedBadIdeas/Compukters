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

package ru.lazyhat.compukters.ide.client

data class IdeClientLimits(
    val eventQueueCapacity: Int = 256,
    val projectRows: Int = 4 * 1024,
    val visibleTreeRows: Int = 8 * 1024,
    val statusUtf8Bytes: Int = 4 * 1024,
    val dialogUtf8Bytes: Int = 16 * 1024,
) {
    init {
        require(eventQueueCapacity > 0) { "event queue capacity must be positive" }
        require(projectRows >= 0) { "project row limit must be non-negative" }
        require(visibleTreeRows >= 0) { "visible tree row limit must be non-negative" }
        require(statusUtf8Bytes >= 0) { "status byte limit must be non-negative" }
        require(dialogUtf8Bytes >= 0) { "dialog byte limit must be non-negative" }
    }
}
