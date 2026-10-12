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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayPointerTest {
    @Test
    fun `focus cancellation tracks physical release before allowing another press`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        val controller = DisplayPointerController()
        val target = DisplayPointerTarget(canvas, 2, 3)
        controller.update("player", 0, true, target)
        canvas.pollPointer(owner)
        controller.cancel("player", 1, true)
        assertEquals(3, canvas.pollPointer(owner)?.kind)
        controller.update("player", 2, true, target)
        assertNull(canvas.pollPointer(owner))
        controller.cancel("player", 3, false)
        controller.update("player", 4, true, target)
        assertEquals(0, canvas.pollPointer(owner)?.kind)
    }

    @Test
    fun `movement coalesces while lifecycle and overflow cancellation remain bounded`() {
        val queue = DisplayPointerQueue()
        queue.cancel()
        assertNull(queue.poll())
        assertFalse(queue.offer(DisplayPointerEvent(1, 1, 1, 1)))
        assertTrue(queue.offer(DisplayPointerEvent(0, 1, 0, 0)))
        repeat(100) { assertTrue(queue.offer(DisplayPointerEvent(1, 1, it, it))) }
        assertTrue(queue.offer(DisplayPointerEvent(2, 1, 99, 99)))
        assertEquals(0, queue.poll()?.kind)
        assertEquals(DisplayPointerEvent(1, 1, 99, 99), queue.poll())
        assertEquals(2, queue.poll()?.kind)
        assertNull(queue.poll())
        repeat(32) {
            assertTrue(queue.offer(DisplayPointerEvent(0, it + 2, 0, 0)))
            assertTrue(queue.offer(DisplayPointerEvent(2, it + 2, 0, 0)))
        }
        assertFalse(queue.offer(DisplayPointerEvent(0, 100, 0, 0)))
        assertEquals(DisplayPointerEvent(3, 0, 0, 0), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `held gestures cancel on leaving and require release before reentry`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        val controller = DisplayPointerController()
        val target = DisplayPointerTarget(canvas, 2, 3)
        controller.update("player", 0, true, target)
        val down = requireNotNull(canvas.pollPointer(owner))
        assertEquals(0, down.kind)
        controller.update("player", 1, true, target.copy(x = 8))
        assertEquals(DisplayPointerEvent(1, down.gesture, 8, 3), canvas.pollPointer(owner))
        controller.update("player", 2, true, null)
        assertEquals(3, canvas.pollPointer(owner)?.kind)
        controller.update("player", 3, true, target)
        assertNull(canvas.pollPointer(owner))
        controller.update("player", 4, false, null)
        controller.update("player", 5, true, target)
        assertEquals(0, canvas.pollPointer(owner)?.kind)
        controller.update("player", 6, false, null)
        assertEquals(2, canvas.pollPointer(owner)?.kind)
    }

    @Test
    fun `expiry disconnect and published mode changes cancel captures without replay`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        val controller = DisplayPointerController()
        val target = DisplayPointerTarget(canvas, 2, 3)
        controller.update("player", 0, true, target)
        canvas.pollPointer(owner)
        controller.expire(6, setOf("player"))
        assertEquals(3, canvas.pollPointer(owner)?.kind)
        controller.update("player", 7, true, target)
        assertNull(canvas.pollPointer(owner))
        controller.expire(8, emptySet())
        controller.update("player", 9, true, target)
        val gesture = requireNotNull(canvas.pollPointer(owner)).gesture
        canvas.begin(owner)
        canvas.setMode(owner, 1)
        canvas.finish(owner, false)
        assertTrue(canvas.pointerActive(gesture))
        canvas.setMode(owner, 1)
        assertEquals(DisplayPointerEvent(3, 0, 0, 0), canvas.pollPointer(owner))
        assertFalse(canvas.pointerActive(gesture))
        controller.update("player", 10, true, target)
        assertNull(canvas.pollPointer(owner))
        canvas.release(owner)
        canvas.acquire(Any()) { true }
        assertFalse(canvas.pointerActive(gesture))
    }

    @Test
    fun `two players keep separate captures and forged targets cannot enter unwritten screens`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val target = DisplayPointerTarget(canvas, 2, 3)
        val controller = DisplayPointerController()
        controller.update("unowned", 0, true, target)
        val owner = Any()
        canvas.acquire(owner) { true }
        controller.update("unowned", 1, true, target)
        assertNull(canvas.pollPointer(owner))
        controller.update("first", 2, true, target)
        controller.update("second", 2, true, target)
        val first = requireNotNull(canvas.pollPointer(owner))
        val second = requireNotNull(canvas.pollPointer(owner))
        assertTrue(first.gesture != second.gesture)
        controller.update("first", 3, false, null)
        assertEquals(first.gesture, canvas.pollPointer(owner)?.gesture)
        assertTrue(canvas.pointerActive(second.gesture))
        controller.close()
        assertEquals(second.gesture, canvas.pollPointer(owner)?.gesture)
    }

    @Test
    fun `clipping charges and touches only pixels inside the nested viewport`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        val clip = DisplayRegion(4, 5, 3, 2)
        canvas.line(owner, Int.MIN_VALUE, 5, Int.MAX_VALUE, 5, 0xFF0000, clip)
        canvas.mask(owner, 0, 0, 16, 16, 0x00FF00, clip) { _, _ -> true }
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(if (x in 4..6 && y in 5..6) 0x00FF00 else 0, canvas.pixelAt(x, y))
            }
        }
        canvas.line(owner, 0, 0, 15, 15, 0xFFFFFF, DisplayRegion(20, 20, 2, 2))
        assertEquals(0, canvas.pixelAt(0, 0))
    }
}
