/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayPublicationWorkTest {
    private class ManualExecutor : Executor {
        val jobs = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            jobs.addLast(command)
        }

        fun run() = jobs.removeFirst().run()
    }

    @Test
    fun `snapshot owns committed pixels across later drawing and density changes`() {
        val canvas = DisplayCanvas(2, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        canvas.fill(owner, 0, 0, 32, 16, 0x123456)
        val snapshot = canvas.publicationSnapshot()
        val original = canvas.tile(1, 0)
        canvas.begin(owner)
        canvas.fill(owner, 0, 0, 32, 16, 0xABCDEF)
        assertContentEquals(original, canvas.publicationSnapshot().tile(1, 0))
        canvas.finish(owner, true)
        canvas.setMode(owner, 1)
        assertContentEquals(original, snapshot.tile(1, 0))
        assertEquals(0, snapshot.mode)
        val returned = snapshot.tile(1, 0)
        returned.fill(0)
        assertContentEquals(original, snapshot.tile(1, 0))
    }

    @Test
    fun `admission bounds snapshots and coalesces a busy source without capturing it`() {
        val worker = ManualExecutor()
        val owner = ManualExecutor()
        val queue = DisplayWorkQueue<String, Int>(worker, { owner.execute(it) }, 2, 10)
        var captured = 0
        var delivered = 0

        fun submit(
            key: String,
            bytes: Long,
        ) = queue.submit(key, bytes, { ++captured }, { it }, { delivered = it.getOrThrow() })
        assertTrue(submit("a", 6))
        assertFalse(submit("a", 1))
        assertFalse(submit("b", 5))
        assertTrue(submit("b", 4))
        assertFalse(submit("c", 0))
        assertEquals(2, captured)
        worker.run()
        assertEquals(0, delivered)
        assertFalse(submit("a", 1)) // Dispatch retains admission until its owner consumes completion.
        owner.run()
        assertEquals(1, delivered)
        assertTrue(submit("a", 6))
    }

    @Test
    fun `closed owner discards queued completions and refuses new captures`() {
        val worker = ManualExecutor()
        val owner = ManualExecutor()
        val queue = DisplayWorkQueue<String, Int>(worker, { owner.execute(it) })
        var delivered = false
        assertTrue(queue.submit("a", 1, { 1 }, { it }, { delivered = true }))
        worker.run()
        queue.close()
        owner.run()
        assertFalse(delivered)
        assertFalse(queue.submit("b", 1, { error("must not capture") }, { 1 }, {}))
    }

    @Test
    fun `worker failure is delivered on owner and executor rejection releases admission`() {
        val worker = ManualExecutor()
        val owner = ManualExecutor()
        val queue = DisplayWorkQueue<String, Int>(worker, { owner.execute(it) }, 1, 1)
        var failure: Throwable? = null
        assertTrue(queue.submit("a", 1, { 1 }, { error("encode failed") }, { failure = it.exceptionOrNull() }))
        worker.run()
        assertEquals(null, failure)
        owner.run()
        assertEquals("encode failed", failure?.message)
        assertTrue(queue.submit("a", 1, { 2 }, { it }, {}))
        var reject = true
        val rejected =
            DisplayWorkQueue<String, Int>(Executor { if (reject) throw RejectedExecutionException() else it.run() }, { it() }, 1, 1)
        assertFalse(rejected.submit("a", 1, { 1 }, { it }, {}))
        reject = false
        assertTrue(rejected.submit("a", 1, { 1 }, { it }, {}))
    }
}
