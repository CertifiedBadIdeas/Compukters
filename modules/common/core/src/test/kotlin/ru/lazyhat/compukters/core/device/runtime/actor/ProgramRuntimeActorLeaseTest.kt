/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramRuntimeActorLeaseTest {
    private val endpoint = VmActorEndpoint(ComputerId.fromLongs(0, 1), 1)

    @Test
    fun `admission precedes commit and rejected reservation can be retried`() {
        var busy = true
        var admissions = 0
        var commits = 0
        val closed = CompletableFuture<Long?>()
        val lease =
            ProgramRuntimeActorLease(endpoint) {
                admissions++
                if (busy) throw ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL)
                ProgramActorCloseReservation {
                    commits++
                    closed
                }
            }
        val request = ProgramActorHibernation(4, byteArrayOf(), emptyList())
        assertFailsWith<ProgramRuntimeActorRequestException> { lease.reserveClose(request) }
        assertEquals(0, commits)
        busy = false
        val reservation = lease.reserveClose(request)
        assertEquals(0, commits)
        val canceled = reservation.result()
        assertTrue(canceled.cancel(false))
        assertFalse(closed.isDone)
        reservation.commit()
        val other = lease.closeAsync()
        assertFalse(other.isDone)
        closed.complete(7)
        assertEquals(7L, other.join())
        assertEquals(2, admissions)
        assertEquals(1, commits)
    }

    @Test
    fun `reentrant commit joins the same accepted close`() {
        lateinit var lease: ProgramRuntimeActorLease
        var commits = 0
        var nested: CompletableFuture<Long?>? = null
        lease =
            ProgramRuntimeActorLease(endpoint) {
                ProgramActorCloseReservation {
                    commits++
                    nested = lease.closeAsync()
                    CompletableFuture.completedFuture(8)
                }
            }
        assertEquals(8L, lease.closeAsync().join())
        assertEquals(8L, checkNotNull(nested).join())
        assertEquals(1, commits)
    }

    @Test
    fun `failure after commit is terminal and does not submit another close`() {
        var commits = 0
        val lease =
            ProgramRuntimeActorLease(endpoint) {
                ProgramActorCloseReservation {
                    commits++
                    throw IllegalStateException("commit failed")
                }
            }
        assertTrue(lease.closeAsync().isCompletedExceptionally)
        assertTrue(lease.closeAsync().isCompletedExceptionally)
        assertEquals(1, commits)
    }
}
