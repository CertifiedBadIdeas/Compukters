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
    fun `single capability infers its binding owner from external declarations`() =
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
                    module fixture:kinetics
                    version 1.0.0
                    dependencies stdlib:core
                    capability fixture kinetics 1 0
                    """.trimIndent().lines(),
                )

            val resolved = resolveAddonContract(root, contract, AddonAbiLock.empty())

            assertEquals(
                "fixture.kinetics.Bindings.speed",
                resolved.contract.bindings
                    .single()
                    .symbol,
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

    private fun contract(): AddonAuthoringContract =
        AddonAuthoringContract.parse(
            """
            addon fixture
            module fixture:kinetics
            version 1.0.0
            dependencies stdlib:core
            capability fixture kinetics 1 0 fixture.kinetics.Bindings
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
