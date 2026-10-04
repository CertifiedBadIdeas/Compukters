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
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddonContractAuthoringTest {
    @Test
    fun `generated addon hosts complete all scalar result kinds`() =
        withSources(
            """
            package fixture.numeric
            private object Bindings {
                external fun tick(): Long
                external fun coordinate(): Double
                external fun letter(): Char
            }
            """.trimIndent(),
        ) { root ->
            val authoring = contract()
            val resolved = resolveAddonContract(root, authoring, AddonAbiLock.empty())
            val generated = renderAddonHostContract(authoring, resolved.contract)
            kotlin.test.assertTrue("HostResponse.LongSuccess" in generated)
            kotlin.test.assertTrue("HostResponse.DoubleSuccess" in generated)
            kotlin.test.assertTrue("HostResponse.CharSuccess" in generated)
        }

    @Test
    fun `addon derives its internal module and capability from one identity`() =
        withSources(
            """
            package fixture.kinetics
            private object Bindings {
                external fun speed(handle: Int): Float
            }
            """.trimIndent(),
        ) { root ->
            val contract =
                AddonAuthoringContract.parse(
                    """
                    addon fixture
                    version 1.0.0
                    """.trimIndent().lines(),
                )

            val resolved = resolveAddonContract(root, contract, AddonAbiLock.empty())

            assertEquals(
                "fixture.kinetics.Bindings.speed",
                resolved.contract.bindings
                    .single()
                    .symbol,
            )
            assertEquals("fixture:api", resolved.contract.module.toString())
            assertEquals(
                "fixture",
                resolved.contract.schemas
                    .single()
                    .identity.name,
            )
        }

    @Test
    fun `declaration order does not affect locked operation selectors`() =
        withSources(
            """
            package fixture.kinetics
            private object Bindings {
                external fun beta(value: Float): Int
                external fun alpha(value: Int): Float
            }
            """.trimIndent(),
        ) { root ->
            val contract = contract()
            val initial = resolveAddonContract(root, contract, AddonAbiLock.empty())
            writeSource(
                root,
                """
                package fixture.kinetics
                private object Bindings {
                    external fun alpha(value: Int): Float
                    external fun beta(value: Float): Int
                }
                """.trimIndent(),
            )

            val reordered = resolveAddonContract(root, contract, initial.expectedLock)

            assertEquals(initial.expectedLock, reordered.expectedLock)
            assertEquals(
                listOf("alpha", "beta"),
                reordered.contract.bindings
                    .sortedBy { it.operation }
                    .map { it.callableName },
            )
        }

    @Test
    fun `removed operations become tombstones and additions append`() =
        withSources(
            """
            package fixture.kinetics
            private object Bindings {
                external fun alpha(value: Int): Float
                external fun beta(value: Float): Int
            }
            """.trimIndent(),
        ) { root ->
            val contract = contract()
            val initial = resolveAddonContract(root, contract, AddonAbiLock.empty())
            writeSource(
                root,
                """
                package fixture.kinetics
                private object Bindings {
                    external fun beta(value: Float): Int
                    external fun gamma(value: Int): Boolean
                }
                """.trimIndent(),
            )

            val changed = resolveAddonContract(root, contract, initial.expectedLock)

            assertEquals(
                AddonAbiEntryState.TOMBSTONE,
                changed.expectedLock.entries
                    .single { it.symbol.endsWith(".alpha") }
                    .state,
            )
            assertEquals(
                2,
                changed.expectedLock.entries
                    .single { it.symbol.endsWith(".gamma") }
                    .operation,
            )
            assertEquals(
                listOf(1, 2),
                changed.contract.bindings
                    .map { it.operation }
                    .sorted(),
            )
            assertEquals(
                3,
                changed.contract.schemas
                    .single()
                    .operations.size,
            )
        }

    @Test
    fun `lock rejects selector gaps`() {
        assertFailsWith<IllegalArgumentException> {
            AddonAbiLock.parse(
                """
                lock 1
                operation fixture kinetics 1 1 active fixture.kinetics.Bindings.alpha fun(Int):Float
                """.trimIndent().lines(),
            )
        }
    }

    @Test
    fun `nested record responses retain schemas and operation tombstones`() =
        withSources(
            """
            package fixture.physics
            data class Vector(val x: Double, val y: Double)
            data class Snapshot(val tick: Long, val position: Vector)
            private object Bindings { external fun snapshot(): Snapshot }
            """.trimIndent(),
        ) { root ->
            val initial = resolveAddonContract(root, contract(), AddonAbiLock.empty())
            val schema =
                initial.contract.schemas
                    .single()
                    .operations
                    .single()
                    .resultRecord!!
            assertEquals("fixture.physics.Snapshot", schema.typeName)
            assertEquals("fixture.physics.Vector", schema.fields[1].record!!.typeName)
            assertEquals(initial.expectedLock, AddonAbiLock.parse(initial.expectedLock.render().lines()))
            val generated = renderAddonHostContract(contract(), initial.contract)
            kotlin.test.assertTrue("AddonCallResult<Snapshot>" in generated)
            kotlin.test.assertTrue("public data class Vector" in generated)
            kotlin.test.assertTrue("encodeVector(value.position)" in generated)
            kotlin.test.assertTrue("HostResponse.LongSuccess(value.tick)" in generated)
            writeSource(root, "package fixture.physics; private object Bindings { external fun available(): Boolean }")
            val removed = resolveAddonContract(root, contract(), initial.expectedLock)
            assertEquals(AddonAbiEntryState.TOMBSTONE, removed.expectedLock.entries[0].state)
            assertEquals(
                schema,
                removed.contract.schemas
                    .single()
                    .operations[0]
                    .resultRecord,
            )
        }

    @Test
    fun `record field changes change the expected ABI lock`() =
        withSources(
            "package fixture.physics; data class Snapshot(val tick: Long); private object Bindings { external fun snapshot(): Snapshot }",
        ) { root ->
            val initial = resolveAddonContract(root, contract(), AddonAbiLock.empty())
            writeSource(
                root,
                "package fixture.physics; data class Snapshot(val tick: Double); private object Bindings { external fun snapshot(): Snapshot }",
            )
            val changed = resolveAddonContract(root, contract(), initial.expectedLock)
            kotlin.test.assertNotEquals(initial.expectedLock, changed.expectedLock)
        }

    @Test
    fun `record responses reject mutable executable nullable array and cyclic shapes`() {
        listOf(
            "data class Snapshot(var x: Double)",
            "data class Snapshot(val x: Double = 0.0)",
            "data class Snapshot(val x: Double) { init { println(x) } }",
            "data class Snapshot(val x: Double) { fun value(): Double = x }",
            "private data class Snapshot(val x: Double)",
            "data class Snapshot private constructor(val x: Double)",
            "data class Snapshot internal constructor(val x: Double)",
            "data class Snapshot(val x: Double?)",
            "data class Snapshot(val x: DoubleArray)",
            "data class Snapshot(val x: Snapshot)",
        ).forEach { declaration ->
            withSources("package fixture.physics; $declaration; private object Bindings { external fun snapshot(): Snapshot }") { root ->
                assertFailsWith<IllegalArgumentException>(declaration) {
                    resolveAddonContract(root, contract(), AddonAbiLock.empty())
                }
            }
        }
    }

    @Test
    fun `record ABI parser rejects malformed excessive and inconsistent shapes`() {
        listOf(
            "fixture.Snapshot{x:UNIT}",
            "fixture.Snapshot{x:RECORD}",
            "fixture.Snapshot{x:F64,x:F64}",
            "fixture.Snapshot{x:F64}trailing",
            "fixture.Snapshot{}",
            "fixture.Snapshot{x:UNKNOWN}",
            "fixture.Other{x:F64}",
        ).forEach { shape ->
            assertFailsWith<IllegalArgumentException>(shape) {
                AddonAbiLock.parse(listOf("lock 2", "record fixture.Snapshot $shape"))
            }
        }
    }

    private fun contract(): AddonAuthoringContract =
        AddonAuthoringContract.parse(
            """
            addon fixture
            version 1.0.0
            """.trimIndent().lines(),
        )

    private fun withSources(
        source: String,
        test: (Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("compukters-addon-authoring-test-")
        try {
            writeSource(root, source)
            test(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun writeSource(
        root: Path,
        source: String,
    ) {
        root.resolve("fixture/kinetics/Kinetics.kt").also { file ->
            file.parent.createDirectories()
            file.writeText(source)
        }
    }
}
