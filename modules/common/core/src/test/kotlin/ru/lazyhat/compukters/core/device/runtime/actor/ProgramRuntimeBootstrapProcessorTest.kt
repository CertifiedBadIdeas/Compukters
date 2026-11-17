/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmStoreBusyException
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramRuntimeBootstrapProcessorTest {
    @Test
    fun `missing checkpoint boots without advancing Guest and close reports the final generation`() {
        var closes = 0
        val processor = processor(onClose = { closes++ })
        val reply = processor.process(ProgramRuntimeActorCommand.RestoreOrBoot(id, 10, required = true))
        assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(reply.value).result)
        assertEquals(ProgramRuntimeState.Running, reply.state)
        assertEquals(0, reply.retiredInstructions)
        assertEquals(3L, reply.fileSystemGeneration)
        processor.close()
        assertEquals(1, closes)
        assertEquals(3L, processor.closed.get())
    }

    @Test
    fun `store pressure is a retryable result and does not duplicate the successful boot`() {
        var attempts = 0
        val processor = processor(beforeBoot = { if (++attempts == 1) throw VmStoreBusyException("busy") })
        processor.use {
            val first = it.process(ProgramRuntimeActorCommand.StartBoot(id))
            assertEquals(ProgramRuntimeActorFailure.StoreBusy, assertIs<ProgramRuntimeActorValue.Rejected>(first.value).failure)
            val second = it.process(ProgramRuntimeActorCommand.StartBoot(id))
            assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(second.value).result)
            assertEquals(2, attempts)
            assertEquals(0, second.retiredInstructions)
        }
    }

    @Test
    fun `active commands cannot execute against a bootstrap session`() {
        processor().use {
            it.process(ProgramRuntimeActorCommand.StartBoot(id))
            val reply = it.process(ProgramRuntimeActorCommand.SendTerminalText(id, "text"))
            assertIs<ProgramRuntimeActorFailure.InvalidRequest>(assertIs<ProgramRuntimeActorValue.Rejected>(reply.value).failure)
            assertEquals(ProgramRuntimeState.Running, reply.state)
        }
    }

    @Test
    fun `failed native close fails the barrier instead of reporting durable success`() {
        val processor = processor(onClose = { throw VmBridgeException("close failed") })
        processor.process(ProgramRuntimeActorCommand.StartBoot(id))
        assertFailsWith<VmBridgeException> { processor.close() }
        assertTrue(processor.closed.isCompletedExceptionally)
        assertIs<VmBridgeException>(assertFailsWith<ExecutionException> { processor.closed.get() }.cause)
    }

    private fun processor(
        beforeBoot: () -> Unit = {},
        onClose: () -> Unit = {},
    ): ProgramRuntimeBootstrapProcessor {
        val session =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProgramVmSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "filesystemGeneration" -> 3L
                    "confirmRedstoneOutput", "submitRedstoneInput" -> Unit
                    "close" -> onClose()
                    else -> error("bootstrap must not invoke ${method.name}")
                }
            } as ProgramVmSession
        return ProgramRuntimeBootstrapProcessor(
            ProgramRuntimeHost(
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray): ProgramVmSession = error("not an artifact start")

                    override fun boot(): ProgramVmSession {
                        beforeBoot()
                        return session
                    }
                },
            ),
        )
    }

    private val id = ProgramRuntimeRequestId(1)
}
