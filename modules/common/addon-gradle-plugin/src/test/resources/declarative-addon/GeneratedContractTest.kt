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

package fixture

import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonPending
import ru.lazyhat.compukters.api.commands.ProgramCommandProvider
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedContractTest {
    @Test
    fun generatedConstraintsProtectOrdinaryCallsAndCommands() {
        val throttles = mutableListOf<Double>()
        val host = FixtureAddonContract.host(handler { name, args ->
            check(name == "engineSetThrottle")
            throttles += args[1] as Double
            addonCompleted(Unit)
        })
        val commands = assertIs<ProgramCommandProvider>(host).commandBindings
        assertEquals(setOf("engineAim", "engineSetThrottle", "engineTarget", "engineSpeed", "engineOrientation", "engineSnapshot", "engineClose", "engineState", "engineDirection", "snapshotSize", "snapshotClose"), commands.map { it.symbol.substringAfterLast('.') }.toSet())
        val throttle = commands.single { it.symbol.endsWith(".engineSetThrottle") }
        fun values(value: Double) = listOf(VmValue.I32(7), VmValue.F64(value.toBits()))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            assertIs<HostResponse.Failure>(throttle.execute(values(invalid)))
            assertIs<HostResponse.Failure>(assertIs<ProgramAddonDispatch.Completed>(host.dispatch(request("engineSetThrottle", values(invalid)))).response)
        }
        assertTrue(throttles.isEmpty())
        assertEquals(HostResponse.UnitSuccess, throttle.execute(values(0.4)))
        assertEquals(listOf(0.4), throttles)
    }

    @Test
    fun publicHostArgumentsAreReconstructedAndRotationsValidated() {
        val directions = mutableListOf<Vector3d>()
        val states = mutableListOf<State>()
        val host = FixtureAddonContract.host(handler { name, args ->
            assertEquals(2, args.size)
            assertEquals(7, args[0])
            when (name) {
                "engineAim" -> directions += args[1] as Vector3d
                "engineTarget" -> states += args[1] as State
                else -> error(name)
            }
            addonCompleted(Unit)
        })
        val commands = assertIs<ProgramCommandProvider>(host).commandBindings
        fun execute(name: String, values: List<VmValue>) {
            assertEquals(HostResponse.UnitSuccess, commands.single { it.symbol.endsWith(".$name") }.execute(values))
            assertEquals(HostResponse.UnitSuccess, assertIs<ProgramAddonDispatch.Completed>(host.dispatch(request(name, values))).response)
        }
        fun values(vararg components: Double) = listOf(VmValue.I32(7)) + components.map { VmValue.F64(it.toBits()) }
        execute("engineAim", values(1.0, 2.0, 3.0))
        assertEquals(listOf(Vector3d(1.0, 2.0, 3.0), Vector3d(1.0, 2.0, 3.0)), directions)
        for (scale in listOf(2.0, Double.MAX_VALUE, Double.MIN_VALUE)) {
            execute("engineTarget", values(4.0, 5.0, 6.0, scale, 0.0, 0.0, scale))
        }
        assertEquals(6, states.size)
        states.forEach {
            assertEquals(Vector3d(4.0, 5.0, 6.0), it.position)
            val q = it.orientation.quaternion
            assertEquals(0.5, q.x * q.x, 1e-15)
            assertEquals(0.5, q.w * q.w, 1e-15)
            assertEquals(0.0, q.y)
            assertEquals(0.0, q.z)
        }
        for (invalid in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val arguments = values(4.0, 5.0, 6.0, invalid, 0.0, 0.0, 0.0)
            assertIs<HostResponse.Failure>(commands.single { it.symbol.endsWith(".engineTarget") }.execute(arguments))
            assertIs<HostResponse.Failure>(assertIs<ProgramAddonDispatch.Completed>(host.dispatch(request("engineTarget", arguments))).response)
        }
        assertEquals(6, states.size)
    }

    @Test
    fun onlyDeclaredWaitsRetainTheirContinuation() {
        var polls = 0
        val host = FixtureAddonContract.host(handler { _, _ ->
            addonPending<Any> { polls++; null }
        })
        val commands = assertIs<ProgramCommandProvider>(host).commandBindings
        assertTrue(commands.none { it.symbol.endsWith(".engineAwaitState") })
        assertIs<HostResponse.Failure>(commands.single { it.symbol.endsWith(".engineSpeed") }.execute(listOf(VmValue.I32(7))))
        assertEquals(0, polls)
        val immediate = assertIs<ProgramAddonDispatch.Completed>(host.dispatch(request("engineSpeed", listOf(VmValue.I32(7)))))
        assertIs<HostResponse.Failure>(immediate.response)
        assertTrue(host.poll(8).isEmpty())
        assertEquals(0, polls)
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(request("engineAwaitState", listOf(VmValue.I32(7)))))
        assertTrue(host.poll(8).isEmpty())
        assertEquals(1, polls)
    }

    private fun request(name: String, values: List<VmValue>): ProgramAddonRequest {
        val binding = FixtureAddonContract.guestApi(javaClass).bindings.single { it.callableName == name }
        val identity = binding.capability
        return ProgramAddonRequest(VmHostRequestIdentity(1, 1), CapabilityIdentity(identity.namespace, identity.name, identity.abiMajor, identity.abiMinor), binding.operation, values)
    }

    private fun handler(action: (String, Array<out Any?>) -> Any): FixtureCapabilityHandler =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FixtureCapabilityHandler::class.java)) { _, method, arguments ->
            action(method.name, arguments ?: emptyArray())
        } as FixtureCapabilityHandler
}
