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

import declarative.DeclarativeAddonContract
import declarative.DeclarativeCapabilityHandler
import declarative.Quaterniond
import declarative.State
import declarative.StateReply
import declarative.Vector3d
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerAdapter
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerInputs
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralProgramHost
import ru.lazyhat.compukters.core.device.runtime.program.withCommandBatches
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DeclarativeNativeIntegrationTest {
    @Test
    fun generatedValuesAndCommandsCrossTheRealNativeBoundary() {
        val directions = mutableListOf<Vector3d>()
        val states = mutableListOf<State>()
        val handler =
            object : DeclarativeCapabilityHandler {
                override fun valuesInteger(): AddonCallResult<Int> = addonCompleted(Int.MIN_VALUE)

                override fun valuesLongValue(): AddonCallResult<Long> = addonCompleted(Long.MAX_VALUE)

                override fun valuesDecimal(): AddonCallResult<Double> = addonCompleted(-0.0)

                override fun valuesBoolean(): AddonCallResult<Boolean> = addonCompleted(false)

                override fun valuesCharacter(): AddonCallResult<Char> = addonCompleted('λ')

                override fun valuesText(): AddonCallResult<String> = addonCompleted("λ😀")

                override fun valuesBytes(): AddonCallResult<ByteArray> = addonCompleted(byteArrayOf(-1, 0, 7))

                override fun valuesFloating(argument0: Int): AddonCallResult<Double> =
                    addonCompleted(
                        listOf(
                            Double.MIN_VALUE,
                            Double.MAX_VALUE,
                            Double.POSITIVE_INFINITY,
                            Double.NEGATIVE_INFINITY,
                            Double.NaN,
                            -0.0,
                            1.0000000000000002,
                        )[argument0],
                    )

                override fun valuesSingle(argument0: Int): AddonCallResult<Float> =
                    addonCompleted(
                        listOf(
                            Float.MIN_VALUE,
                            Float.MAX_VALUE,
                            Float.POSITIVE_INFINITY,
                            Float.NEGATIVE_INFINITY,
                            Float.NaN,
                            -0.0f,
                            1.0000001f,
                        )[argument0],
                    )

                override fun valuesOversized(): AddonCallResult<String> = addonCompleted("x".repeat(4096))

                override fun engineAim(
                    argument0: Int,
                    argument1: Vector3d,
                ): AddonCallResult<Unit> {
                    assertEquals(7, argument0)
                    if (argument1.x == 4.0) return addonFailed(HostFailureKind.UNAVAILABLE, "unavailable engine")
                    directions += argument1
                    return addonCompleted(Unit)
                }

                override fun engineTarget(
                    argument0: Int,
                    argument1: State,
                ): AddonCallResult<Unit> {
                    assertEquals(7, argument0)
                    states += argument1
                    return addonCompleted(Unit)
                }

                override fun engineState(argument0: Int): AddonCallResult<StateReply> =
                    addonCompleted(StateReply(Vector3d(1.0, 2.0, 3.0), Quaterniond(0.0, 0.0, 0.0, 2.0)))

                override fun engineSnapshot(argument0: Int): AddonCallResult<Int> = addonCompleted(42)

                override fun snapshotClose(argument0: Int): AddonCallResult<Unit> {
                    assertEquals(42, argument0)
                    return addonCompleted(Unit)
                }

                override fun engineSpeed(argument0: Int): AddonCallResult<Float> = addonCompleted(1.0f)

                override fun engineClose(argument0: Int): AddonCallResult<Unit> = addonCompleted(Unit)
            }
        val source =
            """
            import declarative.Engine
            import declarative.Values
            import compukter.commands.*
            import compukter.peripheral.Side
            import kotlin.math.Vector3d
            fun main() {
                val engine = Engine.at(Side.front)
                val state = engine.state()
                check(state.orientation.quaternion.w == 1.0)
                val direction = engine.velocity().direction()
                check(direction.length > 0.999 && direction.length < 1.001)
                check(direction == state.direction())
                engine.aim(state.position)
                engine.target(state)
                val speed = engine.speedCommand()
                val readState = engine.stateCommand()
                val close = engine.closeCommand()
                val snapshot = engine.snapshotCommand()
                val aim = engine.aimCommand(Vector3d(7.0, 8.0, 9.0))
                val commands = listOf(
                    engine.aimCommand(Vector3d(4.0, 5.0, 6.0)),
                    engine.targetCommand(state), aim, speed, readState, close, snapshot,
                )
                val results = batch(commands)
                check(results.size == 7 && results[0].isFailure)
                val measured: Float = results[speed].getOrThrow()
                check(measured == 1.0f)
                val batchedState: declarative.State = results[readState].getOrThrow()
                check(batchedState.position == state.position)
                check(batchedState.orientation.quaternion.w == 1.0)
                results[close].getOrThrow()
                val resource = results[snapshot].getOrThrow()
                for (result in batch(listOf(resource.closeCommand()))) result.getOrThrow()
                var failures = 0
                for (result in results) {
                    if (result.isFailure) failures++ else result.getOrThrow()
                }
                check(failures == 1)
                val reads = listOf(engine.speedCommand(), engine.speedCommand())
                val temperatures = batch(reads)
                for (result in temperatures) {
                    val value: Float = result.getOrThrow()
                    check(value == measured)
                }
                for (command in reads) {
                    val value: Float = temperatures[command].getOrThrow()
                    check(value == measured)
                }
                val duplicates = batch(listOf(speed, speed))
                check(duplicates.getAll(speed).size == 2)
                for (result in duplicates.getAll(speed)) check(result.getOrThrow() == 1.0f)
                try { duplicates[speed]; error("ambiguous command accepted") }
                catch (expected: IllegalArgumentException) {}
                try { results[engine.speedCommand()]; error("foreign command accepted") }
                catch (expected: IllegalArgumentException) {}
                check(results.getAll(engine.speedCommand()).isEmpty())
                check(batch(listOf(speed))[speed].getOrThrow() == 1.0f)
                check(results[speed].getOrThrow() == measured)
                val integer = Values.integerCommand()
                val long = Values.longValueCommand()
                val decimal = Values.decimalCommand()
                val boolean = Values.booleanCommand()
                val character = Values.characterCommand()
                val text = Values.textCommand()
                val bytes = Values.bytesCommand()
                val values = batch(listOf(integer, long, decimal, boolean, character, text, bytes))
                check(values[integer].getOrThrow() == Int.MIN_VALUE)
                check(values[long].getOrThrow() == Long.MAX_VALUE)
                check(1.0 / values[decimal].getOrThrow() == Double.NEGATIVE_INFINITY)
                check(!values[boolean].getOrThrow())
                check(values[character].getOrThrow() == 'λ')
                check(values[text].getOrThrow() == "λ😀")
                val array: ByteArray = values[bytes].getOrThrow()
                check(array.size == 3 && array[0] == (-1).toByte() && array[2] == 7.toByte())
                val numeric = batch(listOf(integer, long, decimal))
                var numericTotal = 0.0
                for (result in numeric) numericTotal += result.getOrThrow().toDouble()
                check(numericTotal == Int.MIN_VALUE.toDouble() + Long.MAX_VALUE.toDouble())
                val oversized = Values.oversizedCommand()
                val bounded = batch(listOf(oversized, integer))
                check(bounded[oversized].isFailure)
                check(bounded[integer].getOrThrow() == Int.MIN_VALUE)
                val doubles = listOf(Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, -0.0, 1.0000000000000002)
                val floats = listOf(Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, -0.0f, 1.0000001f)
                val doubleCommands = ArrayList<Command<Double>>()
                val floatCommands = ArrayList<Command<Float>>()
                for (index in 0 until doubles.size) {
                    doubleCommands.add(Values.floatingCommand(index))
                    floatCommands.add(Values.singleCommand(index))
                }
                val doubleResults = batch(doubleCommands)
                val floatResults = batch(floatCommands)
                for (index in 0 until doubles.size) {
                    val actualDouble: Double = doubleResults[index].getOrThrow()
                    val actualFloat: Float = floatResults[index].getOrThrow()
                    if (index == 4) {
                        check(actualDouble != actualDouble && actualFloat != actualFloat)
                    } else {
                        check(actualDouble == doubles[index] && actualFloat == floats[index])
                    }
                    if (index == 5) {
                        check(1.0 / actualDouble == Double.NEGATIVE_INFINITY)
                        check(1.0f / actualFloat == Float.NEGATIVE_INFINITY)
                    }
                }



            }
            """.trimIndent()
        val artifact = compile(source)
        VmRuntime.install(FfmRuntimeBackend)
        VmRuntime.loadNativeLibrary(propertyPath("compukter.ffi.library"))
        withCommandBatches(DeclarativeAddonContract.host(handler)).use { host ->
            VmSession.open(artifact, listOf(PeripheralProgramHost.SCHEMA) + host.capabilitySchemas).use { session ->
                var halted = false
                var lookups = 0
                var batchRequests = 0
                for (slice in 0 until 1000) {
                    when (val outcome = session.advance(100_000, 10_000, 64)) {
                        is VmOutcome.HostRequestBatch -> {
                            outcome.requests.forEach { request ->
                                val response =
                                    if (request.capability.namespace == "compukters" &&
                                        request.capability.name == "peripheral"
                                    ) {
                                        assertEquals(
                                            listOf(VmValue.StringValue("declarative:engine"), VmValue.I32(0)),
                                            request.arguments,
                                        )
                                        lookups++
                                        HostResponse.IntSuccess(7)
                                    } else {
                                        if (request.capability.name == "commands") {
                                            batchRequests++
                                            assertEquals(2, request.capability.abiMajor)
                                        }
                                        assertIs<ProgramAddonDispatch.Completed>(
                                            host.dispatch(
                                                ProgramAddonRequest(
                                                    request.identity,
                                                    request.capability,
                                                    request.operation,
                                                    request.arguments,
                                                ),
                                            ),
                                        ).response
                                    }
                                session.resume(request.identity, response)
                            }
                        }

                        VmOutcome.SliceExhausted, VmOutcome.WaitingForHostQuota -> {
                            Unit
                        }

                        is VmOutcome.Halted -> {
                            halted = true
                            break
                        }

                        else -> {
                            error("Unexpected native outcome: $outcome")
                        }
                    }
                }
                check(halted) { "Generated Guest program did not finish" }
                assertEquals(1, lookups)
                assertEquals(10, batchRequests)
            }
        }
        assertEquals(listOf(Vector3d(1.0, 2.0, 3.0), Vector3d(7.0, 8.0, 9.0)), directions)
        assertEquals(2, states.size)
        states.forEach {
            assertEquals(Vector3d(1.0, 2.0, 3.0), it.position)
            assertEquals(Quaterniond(0.0, 0.0, 0.0, 1.0), it.orientation.quaternion)
        }
    }

    private fun compile(source: String): ByteArray {
        val base = PlatformBundleCodec.decode(propertyPath("compukters.platform.bundle").readBytes())
        val addonBytes = propertyPath("compukters.declarative.bundle").readBytes()
        val addon = AddonGuestApiBundleCodec.decode(addonBytes)
        val addonHash = Hash256.of(addon.identity.contentHash.toByteArray())
        val identity = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.of(base.identity.contentHash.toByteArray()))
        val request =
            CompileRequest(
                RequestId.of(1u),
                listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
                TargetSettings.KOTLIN_2_4_JVM_17,
                identity,
                WorkerLimits(),
                base.modules.map {
                    TrustedBundleIdentity.of(
                        it.id.toString(),
                        Hash256.of(PlatformBundleCodec.moduleContentHash(it).toByteArray()),
                    )
                } +
                    TrustedBundleIdentity.of(addon.moduleDescriptor.id.toString(), addonHash),
                listOf(TrustedBundlePayload(TrustedBundleIdentity.of(addon.identity.id, addonHash), BinaryValue.of(addonBytes))),
            )
        val temporary = createTempDirectory("declarative-native-")
        try {
            K2CompilerAdapter(K2CompilerInputs(temporary, propertyPath("compukters.worker.jar"), identity), base).use { compiler ->
                val result = compiler.compile(request)
                return assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun propertyPath(name: String): Path = Path.of(requireNotNull(System.getProperty(name)))
}
