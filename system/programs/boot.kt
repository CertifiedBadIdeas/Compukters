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

import compukter.process.Process
import compukter.terminal.Terminal

suspend fun main() {
    val result = Process.run("/rom/shell", 15)
    if (result != 0) Terminal.write("boot failed: " + processFailure(result) + "\n")
}

private fun processFailure(result: Int): String {
    if (result == 1) return "invalid child capabilities"
    if (result == 8) return "invalid executable"
    if (result == 9) return "incompatible program"
    if (result == 10) return "failed to start"
    return "process status"
}
