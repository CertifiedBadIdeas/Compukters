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

package ru.lazyhat.compukters.integration.create

import create.CreateAddonContract
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KineticsHostStateTest {
    @Test
    fun `named acquisition routes every kinetic type and shares handles with side acquisition`() {
        val speedometer = FakeSpeedometer(16f)
        val stressometer = FakeStressometer(4f, 8f)
        val controller = FakeController()
        val state =
            KineticsHostState(
                resolveSide = { side, kind ->
                    speedometer.takeIf { side == 0 && kind == PeripheralKind.SPEEDOMETER }
                },
                resolveName = { name, kind ->
                    val endpoint =
                        when (name to kind) {
                            "wheel" to PeripheralKind.SPEEDOMETER -> speedometer
                            "load" to PeripheralKind.STRESSOMETER -> stressometer
                            "governor" to PeripheralKind.ROTATION_CONTROLLER -> controller
                            else -> null
                        }
                    endpoint?.let(KineticsNamedResolution::Found)
                        ?: KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "missing")
                },
            )
        val host = CreateAddonContract.host(state)

        val sideHandle = host.completed(0, 0).intValue()
        assertEquals(sideHandle, host.completed(11, "wheel").intValue())
        assertEquals(HostResponse.FloatSuccess(4f), host.completed(4, host.completed(12, "load").intValue()))
        val controllerHandle = host.completed(10, "governor").intValue()
        assertEquals(HostResponse.IntSuccess(32), host.completed(9, controllerHandle, 32))
    }

    @Test
    fun `named acquisition preserves lookup failures without retaining a handle`() {
        val failures =
            mapOf(
                "missing" to KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "missing device"),
                "ambiguous" to KineticsNamedResolution.Failed(HostFailureKind.OTHER, "ambiguous device"),
                "invalid name" to KineticsNamedResolution.Failed(HostFailureKind.OTHER, "invalid name"),
                "unloaded" to KineticsNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "unloaded device"),
            )
        val state =
            KineticsHostState(
                resolveSide = { _, _ -> null },
                resolveName = { name, _ -> checkNotNull(failures[name]) },
            )
        val host = CreateAddonContract.host(state)

        failures.forEach { (name, failure) ->
            assertEquals(HostResponse.Failure(failure.kind, failure.detail), host.completed(11, name))
        }
    }

    @Test
    fun `speedometer handle is stable and its Float wait completes after a change`() {
        val speedometer = FakeSpeedometer(16f)
        val state = KineticsHostState { side, kind -> if (side == 0 && kind == PeripheralKind.SPEEDOMETER) speedometer else null }
        val host = CreateAddonContract.host(state)

        val firstHandle = host.completed(0, 0).intValue()
        val secondHandle = host.completed(0, 0).intValue()
        assertEquals(firstHandle, secondHandle)
        assertEquals(HostResponse.FloatSuccess(16f), host.completed(1, firstHandle))

        val wait = request(2, firstHandle, requestId = 4)
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(wait))
        assertTrue(host.poll(8).isEmpty())
        speedometer.speed = -32.5f
        assertEquals(
            listOf(
                ProgramAddonCompletion(wait.identity, HostResponse.FloatSuccess(-32.5f)),
            ),
            host.poll(8),
        )
    }

    @Test
    fun `stress wait observes either stress or capacity while controller returns its clamped target`() {
        val stressometer = FakeStressometer(4f, 8f)
        val controller = FakeController()
        val state =
            KineticsHostState { side, kind ->
                when (side to kind) {
                    2 to PeripheralKind.STRESSOMETER -> stressometer
                    1 to PeripheralKind.ROTATION_CONTROLLER -> controller
                    else -> null
                }
            }
        val host = CreateAddonContract.host(state)
        val stressHandle = host.completed(3, 2).intValue()
        val controllerHandle = host.completed(7, 1).intValue()

        assertEquals(HostResponse.FloatSuccess(4f), host.completed(4, stressHandle))
        assertEquals(HostResponse.FloatSuccess(8f), host.completed(5, stressHandle))
        val wait = request(6, stressHandle, requestId = 8)
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(wait))
        stressometer.capacity = 16f
        assertEquals(HostResponse.UnitSuccess, host.poll(1).single().response)

        assertEquals(HostResponse.IntSuccess(0), host.completed(8, controllerHandle))
        assertEquals(HostResponse.IntSuccess(256), host.completed(9, controllerHandle, 1_000))
        assertEquals(HostResponse.IntSuccess(256), host.completed(8, controllerHandle))
    }

    @Test
    fun `removed peripheral never rebinds an existing handle`() {
        val first = FakeSpeedometer(16f)
        val second = FakeSpeedometer(32f)
        var current = first
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) current else null }
        val host = CreateAddonContract.host(state)
        val oldHandle = host.completed(0, 0).intValue()
        val wait = request(2, oldHandle, requestId = 12)
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(wait))

        first.valid = false
        current = second
        val failure = assertIs<HostResponse.Failure>(host.completed(1, oldHandle))
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertEquals("Create kinetic device was removed, replaced, or unloaded", failure.detail)
        assertEquals(
            failure,
            assertIs<HostResponse.Failure>(host.poll(1).single().response),
        )
        val newHandle = host.completed(0, 0).intValue()

        assertTrue(newHandle != oldHandle)
        assertEquals(HostResponse.FloatSuccess(32f), host.completed(1, newHandle))
        assertIs<HostResponse.Failure>(host.completed(1, oldHandle))
    }

    @Test
    fun `missing wrong and unknown attachments fail without selecting another device`() {
        val stressometer = FakeStressometer(4f, 8f)
        val state = KineticsHostState { side, kind -> if (side == 3 && kind == PeripheralKind.STRESSOMETER) stressometer else null }
        val host = CreateAddonContract.host(state)

        assertEquals(
            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "No matching Create kinetic device is attached on that side"),
            host.completed(0, 3),
        )
        assertEquals(
            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "No matching Create kinetic device is attached on that side"),
            host.completed(0, 2),
        )
        assertEquals(
            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Create kinetic device handle is unavailable"),
            host.completed(1, 99),
        )
        assertEquals(HostResponse.FloatSuccess(4f), host.completed(4, host.completed(3, 3).intValue()))
    }

    @Test
    fun `Float responses preserve non-finite values and signed zero changes`() {
        val speedometer = FakeSpeedometer(Float.POSITIVE_INFINITY)
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) speedometer else null }
        val host = CreateAddonContract.host(state)
        val handle = host.completed(0, 0).intValue()

        assertEquals(Float.POSITIVE_INFINITY.toBits(), assertIs<HostResponse.FloatSuccess>(host.completed(1, handle)).value.toBits())
        speedometer.speed = Float.NaN
        assertTrue(assertIs<HostResponse.FloatSuccess>(host.completed(1, handle)).value.isNaN())
        speedometer.speed = 0f
        val wait = request(2, handle, requestId = 20)
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(wait))
        speedometer.speed = -0f
        assertEquals((-0f).toBits(), assertIs<HostResponse.FloatSuccess>(host.poll(1).single().response).value.toBits())
    }

    @Test
    fun `handle and wait limits are bounded and reset cancels retained state`() {
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) FakeSpeedometer(0f) else null }
        val host = CreateAddonContract.host(state)
        repeat(64) { assertIs<HostResponse.IntSuccess>(host.completed(0, 0)) }
        assertEquals(
            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Create kinetic device handle limit was reached"),
            host.completed(0, 0),
        )

        host.reset()
        val speedometer = FakeSpeedometer(0f)
        val waiting = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) speedometer else null }
        val waitingHost = CreateAddonContract.host(waiting)
        val handle = waitingHost.completed(0, 0).intValue()
        repeat(64) { requestId ->
            assertEquals(ProgramAddonDispatch.Pending, waitingHost.dispatch(request(2, handle, requestId = 1_000L + requestId)))
        }
        assertEquals(
            HostResponse.Failure(HostFailureKind.UNAVAILABLE, "Addon pending request limit was reached"),
            assertIs<ProgramAddonDispatch.Completed>(waitingHost.dispatch(request(2, handle, requestId = 2_000)))
                .response
                .let { assertIs<HostResponse.Failure>(it) },
        )
        waitingHost.reset()
        speedometer.speed = 1f
        assertTrue(waitingHost.poll(64).isEmpty())
    }

    private fun ProgramAddonHost.completed(
        operation: Int,
        vararg arguments: Int,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, nextRequestId++),
                    CREATE,
                    operation,
                    arguments.map(VmValue::I32),
                ),
            ),
        ).response

    private fun ProgramAddonHost.completed(
        operation: Int,
        argument: String,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, nextRequestId++),
                    CREATE,
                    operation,
                    listOf(VmValue.StringValue(argument)),
                ),
            ),
        ).response

    private fun request(
        operation: Int,
        argument: Int,
        requestId: Long,
    ) = ProgramAddonRequest(VmHostRequestIdentity(1, requestId), CREATE, operation, listOf(VmValue.I32(argument)))

    private fun HostResponse.intValue(): Int = assertIs<HostResponse.IntSuccess>(this).value

    private class FakeSpeedometer(
        var speed: Float,
    ) : SpeedometerAccess {
        var valid = true
        override val identity: Any = this

        override fun valid(): Boolean = valid

        override fun speed(): Float = speed
    }

    private class FakeStressometer(
        var stress: Float,
        var capacity: Float,
    ) : StressometerAccess {
        override val identity: Any = this

        override fun valid(): Boolean = true

        override fun stress(): Float = stress

        override fun capacity(): Float = capacity
    }

    private class FakeController : RotationControllerAccess {
        private var speed = 0
        override val identity: Any = this

        override fun valid(): Boolean = true

        override fun targetSpeed(): Int = speed

        override fun setTargetSpeed(speed: Int): Int {
            this.speed = speed.coerceIn(-256, 256)
            return this.speed
        }
    }

    private companion object {
        val CREATE = CapabilityIdentity("create", "create", 1, 0)
        var nextRequestId = 100L
    }
}
