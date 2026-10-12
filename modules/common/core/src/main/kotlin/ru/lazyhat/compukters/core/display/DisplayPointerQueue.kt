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

package ru.lazyhat.compukters.core.display

/** Gesture IDs are opaque and scoped to one screen's transient input stream. Zero cancels all gestures. */
data class DisplayPointerEvent(
    val kind: Int,
    val gesture: Int,
    val x: Int,
    val y: Int,
)

/** Bounded transient lifecycle queue. Adjacent moves coalesce; overflow cancels every captured gesture. */
class DisplayPointerQueue {
    private val queue = ArrayDeque<DisplayPointerEvent>()
    private val active = mutableSetOf<Int>()

    fun offer(event: DisplayPointerEvent): Boolean {
        require(event.kind in 0..3 && event.gesture > 0)
        if (event.kind == 0) {
            if (event.gesture in active || active.size >= 8) return false
        } else if (event.gesture !in active) {
            return false
        }
        if (event.kind == 1 && queue.lastOrNull()?.let { it.kind == 1 && it.gesture == event.gesture } == true) {
            queue.removeLast()
        }
        if (queue.size >= 64) {
            cancel()
            return false
        }
        if (event.kind == 0) active.add(event.gesture)
        if (event.kind >= 2) active.remove(event.gesture)
        queue.addLast(event)
        return true
    }

    fun poll(): DisplayPointerEvent? = queue.removeFirstOrNull()

    fun contains(gesture: Int): Boolean = gesture in active

    fun cancel() {
        if (queue.isEmpty() && active.isEmpty()) return
        queue.clear()
        active.clear()
        queue.addLast(DisplayPointerEvent(3, 0, 0, 0))
    }

    fun clear() {
        queue.clear()
        active.clear()
    }
}
