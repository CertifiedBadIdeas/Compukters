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

@file:Suppress("UNUSED_PARAMETER")

package compukter.terminal

object Terminal {
    fun write(payload: String): Unit = Unit

    fun erasePrevious(): Unit = Unit

    fun clear(): Unit = Unit

    suspend fun awaitEvent(): Int = 0

    fun eventText(): String = ""

    fun eventKey(): Int = 0

    fun eventAction(): Int = 0

    fun eventModifiers(): Int = 0

    fun finishEvent(): Unit = Unit
}
