/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFleetBootstrapTest {
    @Test
    fun `transition close and service shutdown wait for predecessor without owner pumping`() {
        val owner = Thread.currentThread()
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        val reservations = AtomicInteger()
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                {},
                {},
                {
                    reservations.incrementAndGet()
                    AutoCloseable { reservations.decrementAndGet() }
                },
                { _, _ -> error("closing transition must not activate") },
                {},
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        val predecessor = CompletableFuture<Long>()
        val endpoint = endpoint(1)
        val request =
            checkNotNull(
                bootstrap.transition(
                    endpoint,
                    host(boots, closes) {
                        check(Thread.currentThread() !== owner)
                    },
                    null,
                    0,
                    ProgramRuntimeActorCommand.Reboot(ProgramRuntimeRequestId(1)),
                    predecessor,
                ),
            )
        assertFalse(request.isDone)
        assertEquals(0, boots.get())
        val closed = checkNotNull(bootstrap.stop(endpoint))
        val shutdown = bootstrap.closeAsync()
        assertTrue(request.isCompletedExceptionally)
        assertFalse(closed.isDone)
        assertFalse(shutdown.isDone)
        predecessor.complete(7)
        closed.get(5, TimeUnit.SECONDS)
        shutdown.get(5, TimeUnit.SECONDS)
        assertEquals(0, boots.get())
        assertEquals(0, reservations.get())
    }

    @Test
    fun `failed power predecessor cannot boot and only its request fails`() {
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                {},
                {},
                { AutoCloseable {} },
                { _, _ -> error("failed predecessor must not activate") },
                {},
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        val predecessor = CompletableFuture<Long>()
        val request =
            checkNotNull(
                bootstrap.transition(
                    endpoint(1),
                    host(boots, closes) {},
                    null,
                    0,
                    ProgramRuntimeActorCommand.Reboot(ProgramRuntimeRequestId(1)),
                    predecessor,
                ),
            )
        predecessor.completeExceptionally(IllegalStateException("native stop failed"))
        await {
            bootstrap.pump(8, Long.MAX_VALUE)
            request.isDone
        }
        assertTrue(request.isCompletedExceptionally)
        bootstrap.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(0, boots.get())
    }

    @Test
    fun `transfer pressure retains completed boot and delivers its result only on owner`() {
        val owner = Thread.currentThread()
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        val reservations = AtomicInteger()
        var refusals = 2
        var activations = 0
        var attempts = 0
        val ready = CountDownLatch(1)
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                { check(Thread.currentThread() === owner) },
                { ready.countDown() },
                {
                    reservations.incrementAndGet()
                    AutoCloseable { reservations.decrementAndGet() }
                },
                { _, tick ->
                    assertEquals(7L, tick)
                    attempts++
                    if (refusals-- > 0) throw VmFleetBusyException("test pressure")
                    activations++
                },
                {},
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        val host = host(boots, closes) { check(Thread.currentThread() !== owner) }
        val endpoint = endpoint(1)
        bootstrap.attach(endpoint, host)
        val request = bootstrap.request(endpoint, ProgramRuntimeActorCommand.RestoreOrBoot(ProgramRuntimeRequestId(1), 7))
        assertTrue(bootstrap.request(endpoint, ProgramRuntimeActorCommand.StartBoot(ProgramRuntimeRequestId(2))).isCompletedExceptionally)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(0, bootstrap.pump(1, Long.MAX_VALUE, 0))
        assertEquals(0, attempts)
        assertFalse(request.isDone)
        await {
            bootstrap.pump(1, Long.MAX_VALUE)
            request.isDone
        }
        assertEquals(1, boots.get())
        assertEquals(1, activations)
        assertEquals(3, attempts)
        assertEquals(0, reservations.get())
        assertEquals(0, bootstrap.pendingRequests)
        bootstrap.closeAsync().get(5, TimeUnit.SECONDS)
        host.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `close drains accepted boot without owner pump and supports reentrant shutdown`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        val reservations = AtomicInteger()
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                {},
                {},
                {
                    reservations.incrementAndGet()
                    AutoCloseable { reservations.decrementAndGet() }
                },
                { _, _ -> error("canceled boot must not activate") },
                { bootstrap.closeAsync() },
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        val lease =
            bootstrap.attach(
                endpoint(1),
                host(boots, closes) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                },
            )
        val request = bootstrap.request(lease.endpoint, ProgramRuntimeActorCommand.RestoreOrBoot(ProgramRuntimeRequestId(1), 0))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closing = lease.closeAsync()
        assertTrue(request.isCompletedExceptionally)
        assertFalse(closing.isDone)
        release.countDown()
        closing.get(5, TimeUnit.SECONDS)
        bootstrap.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(1, boots.get())
        assertEquals(1, closes.get())
        assertEquals(0, reservations.get())
        assertEquals(0, bootstrap.pendingRequests)
    }

    @Test
    fun `result cancellation retains admission and close removes undelivered result`() {
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        val reservations = AtomicInteger()
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                {},
                {},
                {
                    reservations.incrementAndGet()
                    AutoCloseable { reservations.decrementAndGet() }
                },
                { _, _ -> error("canceled actor must not activate") },
                {},
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        val lease = bootstrap.attach(endpoint(1), host(boots, closes) {})
        val request = bootstrap.request(lease.endpoint, ProgramRuntimeActorCommand.RestoreOrBoot(ProgramRuntimeRequestId(1), 0))
        request.cancel(false)
        await { boots.get() == 1 }
        assertEquals(1, reservations.get())
        assertEquals(1, bootstrap.pendingRequests)
        lease.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(0, bootstrap.pump(8, Long.MAX_VALUE))
        bootstrap.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(1, closes.get())
        assertEquals(0, reservations.get())
    }

    @Test
    fun `one boot failure closes only its host and does not stop another bootstrap`() {
        val boots = AtomicInteger()
        val closes = AtomicInteger()
        val reservations = AtomicInteger()
        var activated = 0
        var canceled = 0
        lateinit var bootstrap: NativeFleetBootstrap
        bootstrap =
            NativeFleetBootstrap(
                {},
                {},
                {
                    reservations.incrementAndGet()
                    AutoCloseable { reservations.decrementAndGet() }
                },
                { _, _ -> activated++ },
                { canceled++ },
                System::nanoTime,
                { endpoint, request -> bootstrap.prepareClose(endpoint, request) },
            )
        bootstrap.attach(endpoint(1), host(boots, closes) { error("bad boot") })
        val healthy = host(boots, closes) {}
        bootstrap.attach(endpoint(2), healthy)
        val failed = bootstrap.request(endpoint(1), ProgramRuntimeActorCommand.RestoreOrBoot(ProgramRuntimeRequestId(1), 0))
        val success = bootstrap.request(endpoint(2), ProgramRuntimeActorCommand.RestoreOrBoot(ProgramRuntimeRequestId(2), 0))
        await {
            bootstrap.pump(8, Long.MAX_VALUE)
            failed.isDone && success.isDone
        }
        assertTrue(failed.isCompletedExceptionally)
        assertFalse(success.isCompletedExceptionally)
        assertEquals(1, activated)
        assertEquals(1, canceled)
        bootstrap.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(0, reservations.get())
        healthy.close()
        assertEquals(1, closes.get())
    }

    private fun endpoint(id: Long) = VmActorEndpoint(ComputerId.fromLongs(0, id), 1)

    private fun host(
        boots: AtomicInteger,
        closes: AtomicInteger,
        duringBoot: () -> Unit,
    ): ProgramRuntimeHost {
        val session =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProgramVmSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "filesystemGeneration" -> {
                        3L
                    }

                    "confirmRedstoneOutput", "submitRedstoneInput" -> {
                        Unit
                    }

                    "close" -> {
                        closes.incrementAndGet()
                        Unit
                    }

                    else -> {
                        error("unexpected session operation: ${method.name}")
                    }
                }
            } as ProgramVmSession
        return ProgramRuntimeHost(
            object : ProgramVmSessionFactory {
                override fun open(artifact: ByteArray): ProgramVmSession = error("artifact start is outside bootstrap")

                override fun boot(): ProgramVmSession {
                    duringBoot()
                    boots.incrementAndGet()
                    return session
                }
            },
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "bootstrap stalled" }
            Thread.yield()
        }
    }
}
