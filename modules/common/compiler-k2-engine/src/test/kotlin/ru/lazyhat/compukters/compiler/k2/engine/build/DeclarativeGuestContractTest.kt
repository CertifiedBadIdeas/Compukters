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

package ru.lazyhat.compukters.compiler.k2.engine.build

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeclarativeGuestContractTest {
    @Test
    fun `byte arrays are scalar responses rather than record candidates`() =
        contract(
            "@GuestService interface Api { @GuestRead fun read(): ByteArray }",
        ) { model ->
            val root = Files.createTempDirectory("byte-responses")
            try {
                writeGeneratedGuestSources(model, root)
                val resolved = resolveAddonContract(root, AddonAuthoringContract("fixture", "1.0"), AddonAbiLock.empty())
                assertEquals(
                    ru.lazyhat.compukters.addon.api.AddonCapabilityValueType.BYTE_ARRAY,
                    resolved.contract.schemas
                        .single()
                        .operations
                        .single()
                        .result,
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `owned resources use SDK wrappers while retaining scalar handle bindings`() =
        contract(
            """
        @GuestResource(bindings = "Bindings") interface Snapshot {
            @GuestRead(binding = "snapshotSize") fun size(): Int
            @GuestCall(binding = "closeSnapshot") fun close(): Unit
        }
        @GuestDevice("fixture:ticker", bindings = "Bindings") interface Ticker {
            @GuestRead(binding = "snapshot") fun snapshot(): Snapshot
        }
        """,
        ) { model ->
            val generated = generateGuestContractSources(model)
            val snapshot = generated.getValue("fixture/device/fixture.device.Snapshot.kt")
            assertTrue("internal val handle: Int" in snapshot)
            assertTrue("TypedPeripheralProvider" !in snapshot)
            assertTrue("fixture.device.Snapshot(reply)" in generated.getValue("fixture/device/fixture.device.Ticker.kt"))
            val root = Files.createTempDirectory("resource-handles")
            try {
                writeGeneratedGuestSources(model, root)
                val resolved = resolveAddonContract(root, AddonAuthoringContract("fixture", "1.0"), AddonAbiLock.empty())
                assertEquals(
                    "fun(Int):Int",
                    resolved.contract.bindings
                        .single { it.callableName == "snapshot" }
                        .signature,
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `service-owned external identities survive declaration migration`() =
        contract(
            "@GuestService(bindings = \"Api\") interface Api { @GuestRead(binding = \"read\") fun read(): Int }",
        ) { model ->
            val generated = generateGuestContractSources(model)
            assertEquals(1, generated.size)
            assertTrue("public external fun read(): Int" in generated.values.single())
        }

    @Test
    fun `one declaration generates a typed device command and a stable binding`() =
        contract(
            """
        @GuestRecord data class State(val throttle: Double)
        @GuestDevice("fixture:engine", bindings = "EngineBindings") interface Engine {
            @GuestRead(binding = "state") fun state(): State
            @GuestCommand(binding = "setThrottle") fun setThrottle(@Finite @DoubleRange(min = 0.0, max = 1.0) throttle: Double): Unit
            @GuestCall(binding = "close") fun close(): Unit
            @GuestWait(binding = "awaitState") fun awaitState(): State
        }
        """,
        ) { model ->
            assertEquals(setOf("fixture.device.EngineBindings.setThrottle"), model.commands)
            assertEquals(
                listOf("DoubleRange"),
                model.operations
                    .single { it.name == "setThrottle" }
                    .parameters
                    .single()
                    .constraints
                    .map { it.kind },
            )
            val files = generateGuestContractSources(model)
            val device = files.getValue("fixture/device/fixture.device.Engine.kt")
            assertTrue("TypedPeripheralProvider<Engine>" in device)
            assertTrue("fun setThrottleCommand(throttle: Double): Command<Unit>" in device)
            assertTrue("closeCommand" !in device && "stateCommand" !in device && "awaitStateCommand" !in device)
            assertEquals(files, generateGuestContractSources(model))
            val state = files.getValue("fixture/device/fixture.device.State.kt")
            assertTrue("public value class State" in state)
            assertTrue("external fun state(handle: Int): State" in files.getValue("fixture/device/fixture.device.EngineBindings.kt"))
        }

    @Test
    fun `rejects executable bodies mutable records and unsupported boundary shapes`() {
        val invalid =
            listOf(
                "@GuestService interface Api { @GuestRead fun value(): Int = 42 }",
                "@GuestService interface Api { val value: Int get() = 42 }",
                "@GuestRecord data class State(var value: Int)",
                "@GuestRecord data class State(val value: Int = 1)",
                "@GuestRecord data class State(val bytes: ByteArray)",
                "@GuestRecord data class State(val value: Int) { init { error(\"work\") } }",
                "@GuestService interface Api<T> { @GuestRead fun value(): T }",
                "@GuestService interface Api { @GuestRead fun value(): List<Int> }",
                "@GuestService interface Api { @GuestRead fun value(): Int? }",
                "@GuestService interface Api { @GuestRead fun value(arg: Int = 1): Int }",
                "@GuestService interface Api { @GuestCommand fun value(): Int }",
                "@GuestService interface Api { @GuestRead fun value(): Unit }",
                "@GuestService interface Api { @GuestCall fun value(@Finite arg: Int): Unit }",
                "@GuestService interface Api { @GuestCall fun value(@DoubleRange(min = 2.0, max = 1.0) arg: Double): Unit }",
                "@GuestService interface Api { @GuestCall fun value(@MaxLength(-1) arg: String): Unit }",
                "@GuestService interface Api { @GuestCommand fun write(): Unit; @GuestRead fun writeCommand(): Int }",
                "fun execute(): Int = 42",
                "@GuestService interface Api { companion object { const val WORK: Int = 1 + 2 } }",
            )
        invalid.forEach { source ->
            val error = assertFailsWith<IllegalArgumentException>(source) { contract(source) {} }
            assertTrue(error.message?.isNotBlank() == true, source)
        }
    }

    @Test
    fun `historical selectors remain locked when new declarations are added`() =
        contract(
            "@GuestService(bindings = \"Bindings\") interface Api { @GuestRead(binding = \"value\") fun value(): Int }",
        ) { model ->
            val root = Files.createTempDirectory("declarative-abi")
            try {
                writeGeneratedGuestSources(model, root)
                val authoring = AddonAuthoringContract("fixture", "1.0")
                val first = resolveAddonContract(root, authoring, AddonAbiLock.empty())
                val second = resolveAddonContract(root, authoring, first.expectedLock)
                assertEquals(first.expectedLock, second.expectedLock)
                assertEquals(
                    first.contract.bindings
                        .single()
                        .operation,
                    second.contract.bindings
                        .single()
                        .operation,
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `mathematical arguments flatten into the same ordered scalar command payload`() =
        contract(
            """
        @GuestRecord data class Target(val position: Vector3d, val orientation: Rotation3d)
        @GuestDevice("fixture:engine") interface Engine {
            @GuestCommand fun aim(target: Target): Unit
        }
        """,
        ) { model ->
            val files = generateGuestContractSources(model)
            val operation = model.operations.single()
            val arguments = GuestBoundaryTypes(model).arguments(operation)
            assertEquals(
                listOf(
                    "handle",
                    "target_position_x",
                    "target_position_y",
                    "target_position_z",
                    "target_orientation_quaternion_x",
                    "target_orientation_quaternion_y",
                    "target_orientation_quaternion_z",
                    "target_orientation_quaternion_w",
                ),
                arguments.map {
                    it.name
                },
            )
            assertEquals(listOf("Int") + List(7) { "Double" }, arguments.map { it.type })
            val device = files.getValue("fixture/device/fixture.device.Engine.kt")
            assertTrue("target.orientation.quaternion.w" in device)
            assertTrue("CommandArgument.double(target.position.x)" in device)
            val root = Files.createTempDirectory("mathematical-arguments")
            try {
                writeGeneratedGuestSources(model, root)
                val resolved = resolveAddonContract(root, AddonAuthoringContract("fixture", "1.0"), AddonAbiLock.empty())
                assertEquals(
                    8,
                    resolved.contract.schemas
                        .single()
                        .operations
                        .single()
                        .arguments.size,
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `rotation results including nested records are reconstructed through validated factories`() =
        contract(
            """
        @GuestRecord data class State(val orientation: Rotation3d, val location: Vector3d)
        @GuestService interface Api {
            @GuestRead fun orientation(): Rotation3d
            @GuestRead fun state(): State
        }
        """,
        ) { model ->
            val boundary = GuestBoundaryTypes(model)
            assertEquals("kotlin.math.Quaterniond", boundary.wireType(model.operations.first().result))
            val files = generateGuestContractSources(model)
            val api = files.getValue("fixture/device/fixture.device.Api.kt")
            assertTrue("kotlin.math.Rotation3d.fromQuaternion(reply)" in api)
            assertTrue("fixture.device.State(kotlin.math.Rotation3d.fromQuaternion(reply.orientation), reply.location)" in api)
            val wire = files.getValue("fixture/device/fixture.device.StateReply.kt")
            assertTrue("orientation: Quaterniond" in wire)
            assertTrue("location: Vector3d" in wire)
        }

    @Test
    fun `flattened limits cycles and generated wire name collisions are rejected`() {
        listOf(
            "@GuestRecord data class Node(val child: Node)\n@GuestService interface Api { @GuestCall fun send(node: Node): Unit }",
            "@GuestRecord data class Node(val orientation: Rotation3d, val child: Node)\n@GuestService interface Api { @GuestRead fun read(): Node }",
            "@GuestService interface Api { @GuestCall fun send(a: Matrix4d, b: Matrix4d, c: Double): Unit }",
            "@GuestService interface Api { @GuestCall fun send(a: Vector3d, a_x: Double): Unit }",
            "@GuestRecord data class State(val orientation: Rotation3d)\n@GuestRecord data class StateReply(val value: Double)\n@GuestService interface Api { @GuestRead fun state(): State }",
        ).forEach { source -> assertFailsWith<IllegalArgumentException>(source) { contract(source) {} } }
    }

    private fun contract(
        text: String,
        inspect: (DeclarativeGuestContract) -> Unit,
    ) {
        val root = Files.createTempDirectory("addon-declarations")
        try {
            val file = root.resolve("fixture/device/Api.kt")
            file.parent.createDirectories()
            file.writeText(
                """
                package fixture.device
                import ru.lazyhat.compukters.api.contracts.GuestDevice
                import ru.lazyhat.compukters.api.contracts.GuestService
                import ru.lazyhat.compukters.api.contracts.GuestRecord
                import ru.lazyhat.compukters.api.contracts.GuestResource
                import ru.lazyhat.compukters.api.contracts.GuestRead
                import ru.lazyhat.compukters.api.contracts.GuestCall
                import ru.lazyhat.compukters.api.contracts.GuestCommand
                import ru.lazyhat.compukters.api.contracts.GuestWait
                import ru.lazyhat.compukters.api.contracts.Finite
                import ru.lazyhat.compukters.api.contracts.DoubleRange
                import ru.lazyhat.compukters.api.contracts.MaxLength
                import ru.lazyhat.compukters.api.contracts.types.Vector3d
                import ru.lazyhat.compukters.api.contracts.types.Matrix4d
                import ru.lazyhat.compukters.api.contracts.types.Rotation3d
                $text
                """.trimIndent(),
            )
            inspect(parseDeclarativeGuestContract(root, "fixture"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
