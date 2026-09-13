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

import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonDispatch
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonRequest
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
    fun `speedometer handle is stable and its Float wait completes after a change`() {
        val speedometer = FakeSpeedometer(16f)
        val state = KineticsHostState { side, kind -> if (side == 0 && kind == PeripheralKind.SPEEDOMETER) speedometer else null }

        val firstHandle = state.completed(0, 0).intValue()
        val secondHandle = state.completed(0, 0).intValue()
        assertEquals(firstHandle, secondHandle)
        assertEquals(HostResponse.FloatSuccess(16f), state.completed(1, firstHandle))

        val wait = request(2, firstHandle, requestId = 4)
        assertEquals(ProgramAddonDispatch.Pending, state.dispatch(wait))
        assertTrue(state.poll(8).isEmpty())
        speedometer.speed = -32.5f
        assertEquals(
            listOf(
                ru.lazyhat.compukters.core.device.runtime.program
                    .ProgramAddonCompletion(wait.identity, HostResponse.FloatSuccess(-32.5f)),
            ),
            state.poll(8),
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
        val stressHandle = state.completed(3, 2).intValue()
        val controllerHandle = state.completed(7, 1).intValue()

        assertEquals(HostResponse.FloatSuccess(4f), state.completed(4, stressHandle))
        assertEquals(HostResponse.FloatSuccess(8f), state.completed(5, stressHandle))
        val wait = request(6, stressHandle, requestId = 8)
        assertEquals(ProgramAddonDispatch.Pending, state.dispatch(wait))
        stressometer.capacity = 16f
        assertEquals(HostResponse.UnitSuccess, state.poll(1).single().response)

        assertEquals(HostResponse.IntSuccess(0), state.completed(8, controllerHandle))
        assertEquals(HostResponse.IntSuccess(256), state.completed(9, controllerHandle, 1_000))
        assertEquals(HostResponse.IntSuccess(256), state.completed(8, controllerHandle))
    }

    @Test
    fun `removed peripheral never rebinds an existing handle`() {
        val first = FakeSpeedometer(16f)
        val second = FakeSpeedometer(32f)
        var current = first
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) current else null }
        val oldHandle = state.completed(0, 0).intValue()
        val wait = request(2, oldHandle, requestId = 12)
        assertEquals(ProgramAddonDispatch.Pending, state.dispatch(wait))

        first.valid = false
        current = second
        val failure = assertIs<HostResponse.Failure>(state.completed(1, oldHandle))
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertEquals(HostFailureKind.INPUT_OUTPUT, assertIs<HostResponse.Failure>(state.poll(1).single().response).kind)
        val newHandle = state.completed(0, 0).intValue()

        assertTrue(newHandle != oldHandle)
        assertEquals(HostResponse.FloatSuccess(32f), state.completed(1, newHandle))
        assertIs<HostResponse.Failure>(state.completed(1, oldHandle))
    }

    @Test
    fun `missing wrong and unknown attachments fail without selecting another device`() {
        val stressometer = FakeStressometer(4f, 8f)
        val state = KineticsHostState { side, kind -> if (side == 3 && kind == PeripheralKind.STRESSOMETER) stressometer else null }

        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(state.completed(0, 3)).kind)
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(state.completed(0, 2)).kind)
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(state.completed(1, 99)).kind)
        assertEquals(HostResponse.FloatSuccess(4f), state.completed(4, state.completed(3, 3).intValue()))
    }

    @Test
    fun `Float responses preserve non-finite values and signed zero changes`() {
        val speedometer = FakeSpeedometer(Float.POSITIVE_INFINITY)
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) speedometer else null }
        val handle = state.completed(0, 0).intValue()

        assertEquals(Float.POSITIVE_INFINITY.toBits(), assertIs<HostResponse.FloatSuccess>(state.completed(1, handle)).value.toBits())
        speedometer.speed = Float.NaN
        assertTrue(assertIs<HostResponse.FloatSuccess>(state.completed(1, handle)).value.isNaN())
        speedometer.speed = 0f
        val wait = request(2, handle, requestId = 20)
        assertEquals(ProgramAddonDispatch.Pending, state.dispatch(wait))
        speedometer.speed = -0f
        assertEquals((-0f).toBits(), assertIs<HostResponse.FloatSuccess>(state.poll(1).single().response).value.toBits())
    }

    @Test
    fun `handle and wait limits are bounded and reset cancels retained state`() {
        val state = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) FakeSpeedometer(0f) else null }
        repeat(64) { assertIs<HostResponse.IntSuccess>(state.completed(0, 0)) }
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(state.completed(0, 0)).kind)

        state.reset()
        val speedometer = FakeSpeedometer(0f)
        val waiting = KineticsHostState { _, kind -> if (kind == PeripheralKind.SPEEDOMETER) speedometer else null }
        val handle = waiting.completed(0, 0).intValue()
        repeat(64) { requestId ->
            assertEquals(ProgramAddonDispatch.Pending, waiting.dispatch(request(2, handle, requestId = 1_000L + requestId)))
        }
        assertEquals(
            HostFailureKind.UNAVAILABLE,
            assertIs<ProgramAddonDispatch.Completed>(waiting.dispatch(request(2, handle, requestId = 2_000)))
                .response
                .let { assertIs<HostResponse.Failure>(it).kind },
        )
        waiting.reset()
        speedometer.speed = 1f
        assertTrue(waiting.poll(64).isEmpty())
    }

    private fun KineticsHostState.completed(
        operation: Int,
        vararg arguments: Int,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, nextRequestId++),
                    CREATE_KINETICS,
                    operation,
                    arguments.map(VmValue::I32),
                ),
            ),
        ).response

    private fun request(
        operation: Int,
        argument: Int,
        requestId: Long,
    ) = ProgramAddonRequest(VmHostRequestIdentity(1, requestId), CREATE_KINETICS, operation, listOf(VmValue.I32(argument)))

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
        val CREATE_KINETICS = CapabilityIdentity("create", "kinetics", 1, 0)
        var nextRequestId = 100L
    }
}
