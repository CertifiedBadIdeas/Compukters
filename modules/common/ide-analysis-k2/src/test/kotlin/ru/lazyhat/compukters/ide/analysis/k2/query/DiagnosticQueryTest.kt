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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticQueryTest {
    @Test
    fun `hash collection analysis admits typed and value class keys`() {
        val source =
            """
            import kotlin.collections.*
            value class DeviceId(val value: Int)
            value class Position(val x: Int, val name: String)
            fun main() {
                val map: MutableMap<DeviceId, Position?> = HashMap<DeviceId, Position?>()
                map[DeviceId(7)] = Position(2, "a")
                check(map.containsKey(DeviceId(7)))
                val read: Map<DeviceId, Position?> = map
                for (entry in read.entries) { println(entry.key.value) }
                val set: MutableSet<Position> = HashSet<Position>()
                set.add(Position(2, "a"))
                set.iterator().remove()
            }
            """.trimIndent()
        for (attachedSources in listOf(false, true)) {
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
            }
        }
    }

    @Test
    fun `native MFVC analysis preserves source and addon nominal types and members`() {
        for (attachedSources in listOf(false, true)) {
            val source =
                """
                import fixture.kinetics.Geometry
                value class Packet(val code: Int, val label: String)
                value class PairValue<T>(val first: T, val second: T)
                value class OptionalFields(val text: String?, val packet: Packet?, val count: Int)
                fun main() {
                    val packet = Packet(42, "text")
                    val pair = PairValue(packet, packet)
                    val boxed: Any = pair
                    check(boxed is PairValue<*>)
                    val point = Geometry.point()
                    check(point.x == 1.0 && packet.code == 42)
                }
                """.trimIndent()
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
                val offset = source.lastIndexOf("point.x") + 1
                val expression =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(
                            fixture.identity,
                            ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
                                .kotlin("main.kt"),
                            offset,
                        ),
                    ) as AnalysisResult.ExpressionInfo
                assertTrue(requireNotNull(expression.value).renderedType.endsWith("Vec2"), expression.toString())
                val completion =
                    fixture.execute(
                        AnalysisQuery.Completion(
                            fixture.identity,
                            ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
                                .kotlin("main.kt"),
                            source.lastIndexOf("point.x") + "point.".length,
                            ru.lazyhat.compukters.ide.analysis.CompletionTrigger.Manual,
                        ),
                    ) as AnalysisResult.Completion
                val items = completion.items
                assertTrue(items.any { it.insertText == "x" }, items.toString())
                assertTrue(items.any { it.insertText == "y" }, items.toString())
                assertTrue(items.any { it.insertText == "moved" }, items.toString())
            }
        }
    }

    @Test
    fun `semantic-only presentation omits diagnostics and retains symbol highlighting`() {
        K2QueryFixture.source("main.kt" to "fun main() { val value = 1; unknownCall(value) }").use { fixture ->
            val query = fixture.presentation()
            val full = fixture.execute(query) as AnalysisResult.Presentation
            val partial = fixture.execute(query.copy(includeDiagnostics = false)) as AnalysisResult.Presentation
            val fullValue = full.value.accept(fixture.identity) as ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance.Active
            val partialValue =
                partial.value.accept(
                    fixture.identity,
                ) as ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance.Active
            assertTrue(fullValue.diagnostics.isNotEmpty())
            assertTrue(partialValue.diagnostics.isEmpty())
            assertEquals(fullValue.semanticTokens, partialValue.semanticTokens)
            assertEquals(false, partial.diagnosticsIncluded)
        }
    }

    @Test
    fun `scope functions resolve without imports in native analysis`() {
        val source = "fun main() { val value = \"text\".let { it.length }; val doubled = with(value) { this * 2 } }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `collection inline callbacks admit non local returns in native analysis`() {
        val source =
            """
            import kotlin.collections.*
            fun search(values: List<Int>): Int {
                values.map { if (it > 0) return it; it }
                values.any { return it }
                return -1
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `suspend declaration is outside the transparent Guest task model`() {
        val source = "suspend fun main() {}"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(
                active.diagnostics.any {
                    it.severity == EditorDiagnosticSeverity.Error &&
                        it.message == "suspend functions are unsupported; Guest tasks suspend transparently" &&
                        it.range == EditorRange(0, "suspend".length)
                },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `Guest Float arithmetic conversions and console API resolve without errors`() {
        val source =
            """
            fun main() {
                val speed: Float = 16.5F
                val scaled = speed * 2 + 1L
                println("speed=${'$'}scaled int=${'$'}{scaled.toInt()}")
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(
                active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `Guest Double arithmetic conversions and console API resolve without errors`() {
        val source =
            """
            fun main() {
                val samples = doubleArrayOf(16.5, 17.5)
                val speed: Double = samples.copyOf(3)[0]
                for (sample in samples) println(sample)
                val scaled = speed * 2 + 1L + 0.5F
                val nullable: Double? = scaled
                println(nullable ?: Double.NaN)
                println("speed=${'$'}scaled int=${'$'}{scaled.toInt()}")
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(
                active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `foreign JVM declarations are outside the native analysis platform`() {
        K2QueryFixture.source("main.kt" to "val forbidden: java.lang.String? = null").use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.any { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `optional platform module is unresolved until selected`() {
        val source = "import compukter.redstone.Redstone\nval level = Redstone.left.get()"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.any { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `core guest API resolves redstone facade without errors`() {
        val source =
            """
            import compukter.redstone.Redstone

            fun main() {
                val level = Redstone.left.get()
                Redstone.right.set(level)
                Redstone.top.set(15, Redstone.Power.DIRECT)
                Redstone.front.await()
                Redstone.back.await(7)
                Redstone.top.awaitAtLeast(7)
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(
                active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `selected addon API resolves typed Float sensors and controller writes`() {
        val source =
            """
            import fixture.kinetics.Kinetics

            fun main() {
                val speed: Float = Kinetics.left.speedometer().speed()
                val stress: Float = Kinetics.right.stressometer().stress()
                val capacity: Float = Kinetics.right.stressometer().capacity()
                val target: Int = Kinetics.top.rotationController().setTargetSpeed(32)
                println(speed + stress + capacity)
                println(target)
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `redstone output mode does not accept a Boolean`() {
        val source =
            """
            import compukter.redstone.Redstone

            fun main() {
                Redstone.right.set(15, true)
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.any { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `removed redstone v1 facade is unresolved`() {
        val source =
            """
            import compukter.redstone.Redstone
            import compukter.redstone.RedstoneSignal

            fun main() {
                Redstone.outputs()
                RedstoneSignal(7)
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.any { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
        }
    }

    @Test
    fun `type error after supplementary character keeps UTF-16 range`() {
        val source = "val emoji = \"😀\"\nval answer: String = 42"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            val offset = source.lastIndexOf('=')

            assertTrue(
                active.diagnostics.any {
                    it.severity == EditorDiagnosticSeverity.Error && it.range == EditorRange(offset, offset + 1)
                },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `presentation returns diagnostics only for its active file`() {
        K2QueryFixture
            .source(
                "a.kt" to "val first: String = 1",
                "nested/b.kt" to "val second: Int = \"bad\"",
            ).use { fixture ->
                val result =
                    fixture.execute(
                        fixture.presentation("a.kt"),
                    ) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

                assertEquals(setOf("a.kt"), active.diagnostics.mapNotNull { it.path?.value }.toSet())
            }
    }

    @Test
    fun `incomplete syntax produces a bounded diagnostic instead of failing analysis`() {
        K2QueryFixture.source("main.kt" to "fun main( {").use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.isNotEmpty())
        }
    }

    @Test
    fun `incomplete for range produces a diagnostic instead of failing expression checkers`() {
        val source =
            """
            fun main() {
                while (true) {
                    for (i in )
                }
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.isNotEmpty())
        }
    }

    @Test
    fun `raw diagnostics beyond the negotiated cap fail explicitly`() {
        K2QueryFixture.source("main.kt" to "val broken: String = 42").use { fixture ->
            assertFailsWith<AnalysisOutputLimitException> {
                fixture.execute(fixture.presentation(), AnalysisLimits(diagnostics = 0))
            }
        }
    }
}
