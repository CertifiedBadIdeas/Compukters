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
