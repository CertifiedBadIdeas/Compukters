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

package compukter.system.vmbench

internal fun parseVmbenchRounds(text: String): Int {
    val maximumRounds = 1_000_000
    // The CLI accepts unsigned decimal digits, not the optional signs allowed by toIntOrNull.
    if (text.startsWith("+") || text.startsWith("-")) return 0
    val value = text.toIntOrNull() ?: return 0
    return if (value >= 1 && value <= maximumRounds) value else 0
}

internal fun runVmbenchCpu(rounds: Int): Int {
    var checksum = 324_508_639
    var round = 0
    while (round < rounds) {
        var lane = checksum xor round
        var iteration = 0
        while (iteration < 1_024) {
            lane = lane * 1_664_525 + 1_013_904_223
            lane = lane xor (lane ushr 16)
            if ((lane and 1) == 0) {
                checksum = checksum + lane
            } else {
                checksum = checksum xor lane
            }
            iteration = iteration + 1
        }
        checksum = checksum xor round
        round = round + 1
    }
    return checksum
}

internal fun runVmbenchAlloc(rounds: Int): Int {
    var checksum = 0
    var round = 0
    while (round < rounds) {
        val values = IntArray(4)
        values[0] = round
        values[1] = round + 1
        values[2] = round + 2
        values[3] = round + 3
        checksum = checksum xor values[round and 3]
        round = round + 1
    }
    return checksum
}
