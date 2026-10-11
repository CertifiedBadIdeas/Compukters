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

package ru.lazyhat.compukters.impl.ide

class IdeClickTracker {
    private var timeNanos = Long.MIN_VALUE
    private var x = Double.NaN
    private var y = Double.NaN
    private var button = Int.MIN_VALUE

    fun click(
        x: Double,
        y: Double,
        button: Int,
        nowNanos: Long,
    ): Boolean {
        val doubleClick =
            this.button == button && nowNanos - timeNanos in 0..250_000_000L &&
                kotlin.math.abs(this.x - x) <= 4.0 && kotlin.math.abs(this.y - y) <= 4.0
        this.x = x
        this.y = y
        this.button = button
        timeNanos = nowNanos
        return doubleClick
    }
}
