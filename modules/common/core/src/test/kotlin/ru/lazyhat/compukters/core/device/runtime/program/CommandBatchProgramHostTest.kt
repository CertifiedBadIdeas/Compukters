/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.api.commands.ProgramCommandBinding
import ru.lazyhat.compukters.api.commands.ProgramCommandProvider
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandBatchProgramHostTest {
    @Test
    fun `one delivery preserves order duplicates and independent errors`() {
        val calls = ArrayList<Int>()
        val first =
            TestHost { value ->
                calls += value
                if (value == 2) HostResponse.Failure(HostFailureKind.UNAVAILABLE, "stale") else HostResponse.UnitSuccess
            }
        val wrapped = withCommandBatches(programAddonHostOf(listOf(first)))
        val commands = listOf(1, 2, 1, 3).map(::command)
        assertTrue(calls.isEmpty())
        val response = dispatch(wrapped, CommandBatchCodec.encode(commands))
        assertContentEquals(listOf(1, 2, 1, 3), calls)
        assertContentEquals(
            CommandBatchCodec.encodeResults(
                listOf(
                    HostResponse.UnitSuccess,
                    HostResponse.Failure(HostFailureKind.UNAVAILABLE, "stale"),
                    HostResponse.UnitSuccess,
                    HostResponse.UnitSuccess,
                ),
            ),
            assertIs<HostResponse.ByteArraySuccess>(response).value,
        )
    }

    @Test
    fun `full packet validation happens before any effect`() {
        val calls = ArrayList<Int>()
        val wrapped =
            withCommandBatches(
                TestHost {
                    calls += it
                    HostResponse.UnitSuccess
                },
            )
        val valid = CommandBatchCodec.encode(listOf(command(1), command(2)))
        val malformed = listOf(byteArrayOf(), valid + 0, valid.copyOf(valid.size - 1), byteArrayOf(1, 1), byteArrayOf(2, 33))
        malformed.forEach { assertIs<HostResponse.Failure>(dispatch(wrapped, it)) }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unavailable and malformed command arguments do not cancel following commands`() {
        val calls = ArrayList<Int>()
        val wrapped =
            withCommandBatches(
                TestHost {
                    calls += it
                    HostResponse.UnitSuccess
                },
            )
        val commands =
            listOf(
                CommandBatchCodec.Command("fixture.Binding.read", emptyList()),
                CommandBatchCodec.Command("fixture.Binding.write", listOf(VmValue.StringValue("wrong type"))),
                command(3),
            )
        val response = assertIs<HostResponse.ByteArraySuccess>(dispatch(wrapped, CommandBatchCodec.encode(commands)))
        assertContentEquals(listOf(3), calls)
        assertContentEquals(
            CommandBatchCodec.encodeResults(
                listOf(
                    HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Command binding is unavailable"),
                    HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Command binding is unavailable"),
                    HostResponse.UnitSuccess,
                ),
            ),
            response.value,
        )
    }

    @Test
    fun `unexpected command exceptions leave subsequent commands runnable`() {
        val calls = ArrayList<Int>()
        val wrapped =
            withCommandBatches(
                TestHost { value ->
                    calls += value
                    if (value == 1) throw IllegalStateException("adapter failed")
                    HostResponse.UnitSuccess
                },
            )
        assertIs<HostResponse.ByteArraySuccess>(dispatch(wrapped, CommandBatchCodec.encode(listOf(command(1), command(2)))))
        assertContentEquals(listOf(1, 2), calls)
    }

    @Test
    fun `typed results must match the registered schema and failures leave later positions runnable`() {
        val wrapped =
            withCommandBatches(
                TestHost(result = HostValueType.I32) {
                    if (it == 2) HostResponse.UnitSuccess else HostResponse.IntSuccess(it)
                },
            )
        val response =
            assertIs<HostResponse.ByteArraySuccess>(dispatch(wrapped, CommandBatchCodec.encode(listOf(command(1), command(2), command(3)))))
        assertContentEquals(
            CommandBatchCodec.encodeResults(
                listOf(
                    HostResponse.IntSuccess(1),
                    HostResponse.Failure(HostFailureKind.OTHER, "Command result does not match schema"),
                    HostResponse.IntSuccess(3),
                ),
            ),
            response.value,
        )
    }

    @Test
    fun `program scopes retain their own command handlers and checkpoint format`() {
        var instances = 0
        val histories = ArrayList<ArrayList<Int>>()
        val scoped =
            programScopedAddonHostOf {
                instances++
                val calls = ArrayList<Int>().also(histories::add)
                withCommandBatches(
                    TestHost {
                        calls += it
                        HostResponse.UnitSuccess
                    },
                )
            }
        scoped.programStarted(1)
        val bytes = CommandBatchCodec.encode(listOf(command(7)))
        dispatch(scoped, bytes, program = 1)
        dispatch(scoped, bytes, program = 0)
        assertEquals(2, instances)
        assertContentEquals(listOf(7), histories[0])
        assertContentEquals(listOf(7), histories[1])
        scoped.programStopped(1)
        assertFailsWith<IllegalArgumentException> { dispatch(scoped, bytes, program = 1) }
        dispatch(scoped, bytes, program = 0)
        assertContentEquals(listOf(7, 7), histories[0])
        scoped.close()
    }

    private class TestHost(
        result: HostValueType = HostValueType.UNIT,
        execute: (Int) -> HostResponse,
    ) : ProgramAddonHost,
        ProgramCommandProvider {
        private val identity = CapabilityIdentity("fixture", "fixture", 1, 0)
        override val capabilitySchemas =
            listOf(
                HostCapabilitySchema(
                    identity,
                    listOf(HostOperationSchema(listOf(HostValueType.I32), result, asynchronous = true)),
                ),
            )
        override val commandBindings =
            listOf(
                ProgramCommandBinding(
                    "fixture.Binding.write",
                    identity,
                    0,
                    listOf(HostValueType.I32),
                ) { execute((it.single() as VmValue.I32).value) },
            )

        override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch = error("ordinary dispatch was not expected")

        override fun checkpoint(): ByteArray = byteArrayOf()

        override fun reset() = Unit
    }

    private fun command(value: Int) = CommandBatchCodec.Command("fixture.Binding.write", listOf(VmValue.I32(value)))

    private fun dispatch(
        host: ProgramAddonHost,
        bytes: ByteArray,
        program: Long = 0,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            host.dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, 1),
                    CommandBatchProgramHost.SCHEMA.identity,
                    0,
                    listOf(VmValue.ByteArrayValue(bytes)),
                    program,
                ),
            ),
        ).response
}
