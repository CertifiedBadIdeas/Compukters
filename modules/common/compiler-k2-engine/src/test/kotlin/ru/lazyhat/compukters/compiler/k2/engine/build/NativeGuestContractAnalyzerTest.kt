/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeGuestContractAnalyzerTest {
    @Test
    fun `binding defaults retain class prefixes and explicit historical names`() =
        fixture(
            """
            @GuestDevice("fixture:engine") interface Engine {
                @GuestRead fun state(): Int
                @GuestCommand fun setThrottle(value: Double): Unit
                @GuestCall(binding = "") fun close(): Unit
            }
            @GuestResource interface Snapshot { @GuestCall fun close(): Unit }
            @GuestService interface Api { @GuestRead(binding = "legacyValue") fun value(): Int }
            """.trimIndent(),
        ) { model ->
            assertEquals(
                setOf(
                    "fixture.EngineBindings.engineState",
                    "fixture.EngineBindings.engineSetThrottle",
                    "fixture.EngineBindings.engineClose",
                    "fixture.SnapshotBindings.snapshotClose",
                    "fixture.ApiBindings.legacyValue",
                ),
                model.operations.map { it.binding }.toSet(),
            )
            assertEquals(setOf("fixture.EngineBindings.engineSetThrottle"), model.commands)
        }

    @Test
    fun `byte array results retain scalar wire semantics`() =
        fixture("@GuestService interface Api { @GuestRead fun read(): ByteArray }") { model ->
            val generated = Files.createTempDirectory("native-byte-array-")
            try {
                writeGeneratedGuestSources(model, generated)
                val resolved =
                    resolveAddonContract(
                        generated,
                        AddonAuthoringContract("fixture", "1.0"),
                        AddonAbiLock.empty(),
                        bindingAliases = model.bindingAliases,
                        resolvedRecords = nativeResultSchemas(model),
                    )
                assertEquals(
                    ru.lazyhat.compukters.addon.api.AddonCapabilityValueType.BYTE_ARRAY,
                    resolved.contract.schemas
                        .single()
                        .operations
                        .single()
                        .result,
                )
            } finally {
                generated.toFile().deleteRecursively()
            }
        }

    @Test
    fun `invalid transport shapes and constraint contracts are rejected natively`() {
        listOf(
            "abstract class Accidental { @GuestRead abstract fun value(): Int }",
            "interface Accidental { @GuestRead fun value(): Int }",
            "class Outer { @GuestService interface Nested { @GuestRead fun value(): Int } }",
            "data class State(val value: Int = 1)\n@GuestService interface Api { @GuestRead fun state(): State }",
            "data class State(val bytes: ByteArray)\n@GuestService interface Api { @GuestRead fun state(): State }",
            "@GuestService interface Api<T> { @GuestRead fun value(): T }",
            "@GuestService interface Api { @GuestRead fun value(): List<Int> }",
            "@GuestService interface Api { @GuestRead fun value(): Int? }",
            "@GuestService interface Api { @GuestRead fun value(arg: Int = 1): Int }",
            "@GuestService interface Api { @GuestCall fun value(@Finite arg: Int): Unit }",
            "@GuestService interface Api { @GuestCall fun value(@DoubleRange(min = 2.0, max = 1.0) arg: Double): Unit }",
            "@GuestService interface Api { @GuestCall fun value(@MaxLength(-1) arg: String): Unit }",
            "@GuestService interface Api { @GuestCommand fun write(): Unit; @GuestRead fun writeCommand(): Int }",
            "data class Node(val child: Node)\n@GuestService interface Api { @GuestCall fun send(node: Node): Unit }",
            "@GuestService interface Api { @GuestCall fun send(a: kotlin.math.Matrix4d, b: kotlin.math.Matrix4d, c: Double): Unit }",
            "@GuestService interface Api { @GuestCall fun send(a: kotlin.math.Vector3d, a_x: Double): Unit }",
        ).forEach { source ->
            assertFailsWith<IllegalArgumentException>(source) {
                fixture(source) { model -> model.operations.forEach { GuestBoundaryTypes(model).arguments(it) } }
            }
        }
    }

    @Test
    fun `native contracts resolve aliases and preserve executable methods without allocating selectors`() =
        fixture(
            """
            import kotlin.math.Vector3d as Vector
            typealias Direction = Vector
            value class Velocity(val vector: Vector) {
                fun direction(): Vector = vector.normalized()
            }
            data class State(val velocity: Velocity) {
                fun direction(): Vector = velocity.direction()
            }
            @GuestDevice("fixture:engine") interface Engine {
                @GuestRead fun state(): State
                @GuestCommand fun aim(direction: Direction): Unit
                fun direction(): Vector = state().direction()
            }
            fun use(): Vector = Engine.first().direction()
            """.trimIndent(),
        ) { model ->
            assertEquals(setOf("state", "aim"), model.operations.map { it.name }.toSet())
            assertEquals(
                "kotlin.math.Vector3d",
                model.operations
                    .single { it.name == "aim" }
                    .parameters
                    .single()
                    .type,
            )
            assertEquals(
                listOf("x", "y", "z"),
                model.boundaryRecords
                    .getValue("kotlin.math.Vector3d")
                    .fields
                    .map { it.name },
            )
            assertEquals(
                "fixture.Velocity",
                model.boundaryRecords
                    .getValue("fixture.State")
                    .fields
                    .single()
                    .type,
            )
            assertTrue(
                model.nativeSources.values
                    .single()
                    .contains("vector.normalized()"),
            )
        }

    @Test
    fun `constraint arguments use K2 constant evaluation`() =
        fixture(
            """
            const val LIMIT: Double = 2.0
            @GuestDevice("fixture:engine") interface Engine {
                @GuestCommand fun set(@DoubleRange(min = -LIMIT, max = LIMIT + 1.0) value: Double): Unit
            }
            """.trimIndent(),
        ) { model ->
            assertEquals(
                GuestArgumentConstraint("DoubleRange", "-2.0", "3.0"),
                model.operations
                    .single()
                    .parameters
                    .single()
                    .constraints
                    .single(),
            )
        }

    @Test
    fun `record factories resolve from native metadata and work for addon types`() =
        fixture(
            """
            import kotlin.math.Vector3d
            import kotlin.math.Rotation3d
            @kotlin.annotation.RecordConstructor(factory = "fromAxis", normalize = "axis")
            value class Direction private constructor(val axis: Vector3d) {
                companion object { fun fromAxis(value: Vector3d): Direction = Direction(value.normalized()) }
            }
            @GuestService interface Api {
                @GuestRead fun orientation(): Rotation3d
                @GuestCommand fun aim(direction: Direction): Unit
            }
            """.trimIndent(),
        ) { model ->
            assertEquals(
                GuestRecordConstruction("fromQuaternion", "quaternion"),
                model.boundaryRecords.getValue("kotlin.math.Rotation3d").construction,
            )
            assertEquals(
                GuestRecordConstruction("fromAxis", "axis"),
                model.boundaryRecords.getValue("fixture.Direction").construction,
            )
            assertEquals("kotlin.math.Quaterniond", GuestBoundaryTypes(model).wireType("kotlin.math.Rotation3d"))
            assertEquals("kotlin.math.Vector3d", GuestBoundaryTypes(model).wireType("fixture.Direction"))
        }

    @Test
    fun `record factories must match the actual primary constructor`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                fixture(
                    """
                    @kotlin.annotation.RecordConstructor(factory = "of")
                    value class Quantity private constructor(val value: Double) {
                        companion object { fun of(value: Int): Quantity = Quantity(value.toDouble()) }
                    }
                    @GuestService interface Api { @GuestRead fun value(): Quantity }
                    """.trimIndent(),
                ) {}
            }
        assertTrue(error.message.orEmpty().contains("record factory must be public"), error.message)
    }

    @Test
    fun `mutable data is rejected at the transport boundary`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                fixture("data class State(var value: Double)\n@GuestService interface Api { @GuestRead fun state(): State }") {}
            }
        assertTrue(error.message.orEmpty().contains("public val fields"), error.message)
    }

    @Test
    fun `server annotations require bodyless methods and commands require Unit`() {
        val body =
            assertFailsWith<IllegalArgumentException> {
                fixture("@GuestService interface Api { @GuestRead fun value(): Double = 1.0 }") {}
            }
        assertTrue(body.message.orEmpty().contains("must have no body"), body.message)
        val result =
            assertFailsWith<IllegalArgumentException> {
                fixture("@GuestService interface Api { @GuestCommand fun value(): Double }") {}
            }
        assertTrue(result.message.orEmpty().contains("must return Unit"), result.message)
    }

    @Test
    fun `authors cannot introduce raw intrinsics or reserved generated declarations`() {
        val intrinsic =
            assertFailsWith<IllegalArgumentException> {
                fixture("external fun unsafe(): Int") {}
            }
        assertTrue(intrinsic.message.orEmpty().contains("Only annotated server operations"), intrinsic.message)
        val reserved =
            assertFailsWith<IllegalArgumentException> {
                fixture("fun __CompuktersUnsafe(): Int = 1") {}
            }
        assertTrue(reserved.message.orEmpty().contains("prefix is reserved"), reserved.message)
    }

    private fun fixture(
        text: String,
        block: (DeclarativeGuestContract) -> Unit,
    ) {
        val root = Files.createTempDirectory("compukters-native-contract-")
        try {
            root.resolve("fixture/Contract.kt").apply {
                parent.createDirectories()
                writeText("package fixture\nimport ru.lazyhat.compukters.api.contracts.*\n$text")
            }
            val platform =
                PlatformBundleCodec.decode(
                    Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.native.contract.platform")))),
                )
            block(analyzeNativeGuestContract(platform, root, "fixture"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
