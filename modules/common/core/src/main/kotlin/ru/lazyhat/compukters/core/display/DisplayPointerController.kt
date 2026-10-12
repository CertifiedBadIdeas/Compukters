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

/** A host-validated aim point; the controller never trusts client-supplied coordinates. */
data class DisplayPointerTarget(
    val canvas: DisplayCanvas,
    val x: Int,
    val y: Int,
)

/** Server-thread gesture lifecycle. Held re-entry after cancellation cannot start a second gesture. */
class DisplayPointerController {
    private class State(
        var tick: Long,
        var updates: Int = 0,
        var capture: Capture? = null,
    )

    private class Capture(
        val target: DisplayPointerTarget,
        val gesture: Int,
        var x: Int,
        var y: Int,
    )

    private val clients = mutableMapOf<Any, State>()
    private var nextGesture = 1

    fun update(
        client: Any,
        tick: Long,
        held: Boolean,
        target: DisplayPointerTarget?,
    ) {
        val existing = clients[client]
        if (!held) {
            existing?.capture?.let { finish(it, 2) }
            clients.remove(client)
            return
        }
        if (existing == null) {
            if (clients.size >= 64) return
            val state = State(tick, 1)
            clients[client] = state
            if (target == null) return
            val gesture = nextGesture
            nextGesture = if (nextGesture == Int.MAX_VALUE) 1 else nextGesture + 1
            if (target.canvas.offerPointer(DisplayPointerEvent(0, gesture, target.x, target.y))) {
                state.capture = Capture(target, gesture, target.x, target.y)
            }
            return
        }
        if (existing.tick == tick && existing.updates >= 2) return
        existing.updates = if (existing.tick == tick) existing.updates + 1 else 1
        existing.tick = tick
        val capture = existing.capture ?: return
        if (target == null || target.canvas !== capture.target.canvas || !target.canvas.pointerActive(capture.gesture)) {
            finish(capture, 3)
            existing.capture = null
            return
        }
        if (target.x != capture.x || target.y != capture.y) {
            if (!target.canvas.offerPointer(DisplayPointerEvent(1, capture.gesture, target.x, target.y))) {
                finish(capture, 3)
                existing.capture = null
            } else {
                capture.x = target.x
                capture.y = target.y
            }
        }
    }

    fun cancel(
        client: Any,
        tick: Long,
        held: Boolean,
    ) {
        val state = clients[client]
        state?.capture?.let { finish(it, 3) }
        if (!held) {
            clients.remove(client)
        } else if (state != null) {
            state.capture = null
            state.tick = tick
        } else if (clients.size < 64) {
            clients[client] = State(tick)
        }
    }

    /** Missing heartbeats cancel capture; tombstones remain until release/disconnect to prevent re-entry. */
    fun expire(
        tick: Long,
        connected: Set<Any>,
    ) {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val (client, state) = iterator.next()
            if (client !in connected || tick - state.tick > 5) {
                state.capture?.let { finish(it, 3) }
                state.capture = null
                if (client !in connected) iterator.remove()
            }
        }
    }

    fun close() {
        clients.values.forEach { it.capture?.let { capture -> finish(capture, 3) } }
        clients.clear()
    }

    private fun finish(
        capture: Capture,
        kind: Int,
    ) {
        capture.target.canvas.offerPointer(DisplayPointerEvent(kind, capture.gesture, capture.x, capture.y))
    }
}
