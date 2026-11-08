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

                override fun engineSpeed(argument0: Int): AddonCallResult<Float> = addonCompleted(1.0f)

                override fun engineClose(argument0: Int): AddonCallResult<Unit> = addonCompleted(Unit)
            }
        val source =
            """
            import declarative.Engine
            import compukter.commands.batch
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
                val results = batch(listOf(
                    engine.aimCommand(Vector3d(4.0, 5.0, 6.0)),
                    engine.targetCommand(state),
                    engine.aimCommand(Vector3d(7.0, 8.0, 9.0)),
                ))
                check(results.size == 3 && results[0].isFailure)
                results[1].getOrThrow()
                results[2].getOrThrow()
            }
            """.trimIndent()
        val artifact = compile(source)
        VmRuntime.install(FfmRuntimeBackend)
        VmRuntime.loadNativeLibrary(propertyPath("compukter.ffi.library"))
        withCommandBatches(DeclarativeAddonContract.host(handler)).use { host ->
            VmSession.open(artifact, listOf(PeripheralProgramHost.SCHEMA) + host.capabilitySchemas).use { session ->
                var halted = false
                var lookups = 0
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
