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
    fun `unresolved identifiers carry a distinct highlight with their full token range`() {
        val source = "fun main() { missingSymbol() }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            val missing =
                active.diagnostics.single {
                    it.highlight == ru.lazyhat.compukters.ide.analysis.EditorDiagnosticHighlight.UnresolvedReference
                }
            val start = source.indexOf("missingSymbol")
            assertEquals(EditorRange(start, start + "missingSymbol".length), missing.range)
        }
    }

    @Test
    fun `random generators and extensions resolve with source and metadata inputs`() {
        val source =
            """
            import kotlin.random.*
            class Zero : Random() { override fun nextBits(bitCount: Int): Int = 0 }
            fun main() {
                val seeded: Random = Random(42L)
                val default: Random = Random.Default
                val factory: (Int) -> Random = ::Random
                val bound: (Int) -> Int = seeded::nextInt
                val unsigned: (UInt) -> UInt = { seeded.nextUInt(it) }
                val i: Int = seeded.nextInt(1..9)
                val l: Long = default.nextLong(1L..9L)
                val u: UInt = seeded.nextUInt(1u..9u)
                val ul: ULong = seeded.nextULong(1uL..9uL)
                val bytes: ByteArray = default.nextBytes(5)
                val ubytes: UByteArray = seeded.nextUBytes(5)
                seeded.nextBytes(bytes, 1, 3)
                seeded.nextUBytes(ubytes, 1, 3)
                val f: Float = Zero().nextFloat()
                val d: Double = seeded.nextDouble(-1.0, 1.0)
                check(factory(42).nextBoolean() || bound(2) == 0 || unsigned(2u) == 0u)
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
    fun `array initializer constructors resolve with source and metadata inputs`() {
        val source =
            """
            import kotlin.math.Vector3f
            class Node(val value: Int)
            class Cell<T>(val value: T)
            fun <T> initializedCells(size: Int, init: (Int) -> Cell<T>): Array<Cell<T>> = Array(size, init)
            fun main() {
                val points = Array(8) { Vector3f(it.toFloat(), 1f, -1f) }
                val nullable: Array<Int?> = Array(3) { if (it == 0) null else it }
                val nodes = Array(3, ::Node)
                val cells = initializedCells(2) { Cell("generic") }
                val strings = Array(2) { "item${'$'}it" }
                check(points[0].x == 0f && nullable[1] == 1 && nodes[2].value == 2 && cells[1].value == "generic" && strings.size == 2)
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
    fun `primitive generic arrays resolve constructors copies and loops with source and metadata inputs`() {
        val values = listOf("true", "1.toByte()", "1.toShort()", "'a'", "1", "1L", "1f", "1.0", "1.toUByte()", "1.toUShort()", "1u", "1uL")
        val calls = values.joinToString("\n") { "exercise(arrayOf($it)); exercise(Array(2) { $it })" }
        val source =
            """
            fun <T : Any> exercise(values: Array<T>) {
                val copy = values.copyOf()
                copy[0] = values[0]
                val nullable = values.copyOf(3)
                nullable[2] = null
                values.copyInto(nullable)
                val any = arrayOf<Any>("x", "y", "z")
                values.copyInto(any)
                for (value in copy) { check(value == values[0]) }
            }
            fun main() { $calls }
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
    fun `reference array loops resolve nullable generic and value class elements with source and metadata inputs`() {
        val source =
            """
            import kotlin.math.Vector3f
            class Node(val value: Int)
            fun <T> last(values: Array<T>, fallback: T): T {
                var result = fallback
                for (value in values) result = value
                return result
            }
            fun main() {
                outer@ for (node in arrayOf<Node?>(Node(1), null)) {
                    try {
                        for (point in Array(3) { Vector3f(it.toFloat(), 0f, 0f) }) {
                            if (point.x == 1f) continue@outer
                            check(node?.value == 1)
                        }
                    } finally { check(true) }
                }
                check(last(arrayOf<Int?>(1, null), null) == null)
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
    fun `do while analysis admits body scoped conditions and local cleanup jumps`() {
        val source =
            """
            fun main() {
                var count = 0
                do {
                    val again = count < 4
                    try {
                        count += 1
                        if (count == 2) continue
                        if (count == 4) break
                    } finally {
                        check(count > 0)
                    }
                } while (again)
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
    fun `labeled loop analysis resolves mixed targets with source and metadata inputs`() {
        val source =
            """
            fun main() {
                outer@ for (value in 1..4) {
                    inner@ do {
                        try {
                            for (element in intArrayOf(1, 2)) {
                                if (element == 1) continue@outer
                                if (value == 4) break@outer
                                break@inner
                            }
                        } finally { check(value > 0) }
                    } while (true)
                }
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
    fun `math analysis admits typed overloads extensions and callable references`() {
        val source =
            """
            import kotlin.math.*
            fun main() {
                val d: Double = sin(PI / 2.0) + E + log(8.0, 2.0)
                val f: Float = sqrt(9.0f) + atan2(1.0f, 1.0f)
                val qualified: Double = kotlin.math.cos(0.0) + kotlin.math.E
                val wave: (Double) -> Double = ::sin
                val next: Double = d.nextTowards(d + 1.0) + d.ulp + d.absoluteValue + d.sign
                val power: Float = f.pow(3).withSign(-1) + f.IEEErem(2.0f)
                val integer: Int = f.roundToInt() + (-7L).sign + abs(-3)
                val wide: Long = d.roundToLong() + max(1L, 2L)
                check(wave(0.0) == 0.0)
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
    fun `hash collection analysis admits typed and value class keys`() {
        val source =
            """
            import kotlin.collections.*
            value class DeviceId(val value: Int)
            value class Position(val x: Int, val name: String)
            fun main() {
                val initial = mutableMapOf<DeviceId, Position?>(DeviceId(1) to null)
                val chosen: Position? = initial.getOrPut(DeviceId(1)) { Position(1, "default") }
                val immutable = mapOf(DeviceId(2) to Position(2, "b"))
                val unique = setOf(DeviceId(1), DeviceId(1))
                val changing = mutableSetOf(Position(1, "a"))
                val pair = Pair(DeviceId(3), chosen)
                val (id, position) = pair
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
    fun `stdlib geometry aliases and vector methods resolve with source and metadata inputs`() {
        for (attachedSources in listOf(false, true)) {
            val source =
                """
                import kotlin.math.*
                import compukter.display.GraphicalDisplay
                fun main() {
                    val point: Position2i = 10 xy 20
                    val size: Size2i = 80 by 24
                    val bounds: Rect2i = point sized size
                    val fractional: Vector2f = (3f xy 4f).normalized()
                    val volume: Position3i = Vector3i(1, 2, 3)
                    val screen = GraphicalDisplay.named("panel")
                    screen.fillRect(bounds, 0xFF0000)
                    screen.text(point, "Ready", scale = 2)
                    check(screen.size.x > 0)
                    check(point in bounds && volume.z == 3)
                    check(fractional.x > 0f)
                }
                """.trimIndent()
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
                val completion =
                    fixture.execute(
                        AnalysisQuery.Completion(
                            fixture.identity,
                            ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
                                .kotlin("main.kt"),
                            source.lastIndexOf("fractional.x") + "fractional.".length,
                            ru.lazyhat.compukters.ide.analysis.CompletionTrigger.Manual,
                        ),
                    ) as AnalysisResult.Completion
                for (name in listOf("x", "y", "length", "normalized", "roundToInt", "floorToInt", "ceilToInt", "dot", "cross", "lerp")) {
                    assertTrue(completion.items.any { it.insertText == name }, completion.items.toString())
                }
            }
        }
    }

    @Test
    fun `stdlib matrix factories and members resolve with source and metadata inputs`() {
        for (attachedSources in listOf(false, true)) {
            val source =
                """
                import kotlin.math.*
                fun main() {
                    val projection = Matrix4f.perspective(1f, 1f, 0.1f, 100f)
                    val model = Matrix4f.translation(Vector3f(0f, 0f, -5f)) * Matrix4f.rotationY(1f)
                    val matrix = projection * model * Matrix4f.scale(2f)
                    val clip: Vector4f = matrix * Vector3f(1f, 2f, 3f)
                    val direction = model.transformDirection(Vector3f(1f, 0f, 0f))
                    val transform: (Vector3f) -> Vector4f = matrix::transformPoint
                    val factory: (Float, Float, Float, Float) -> Matrix4f = Matrix4f::perspective
                    val identity = Matrix4f.identity().transposed()
                    val ortho = Matrix4f.orthographic(-1f, 1f, -1f, 1f, 0f, 10f)
                    if (clip.isInsideClipVolume) println(clip.perspectiveDivide().x)
                    check(matrix.m00 == matrix[0, 0])
                }
                """.trimIndent()
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
                val completion =
                    fixture.execute(
                        AnalysisQuery.Completion(
                            fixture.identity,
                            ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
                                .kotlin("main.kt"),
                            source.lastIndexOf("matrix.m00") + "matrix.".length,
                            ru.lazyhat.compukters.ide.analysis.CompletionTrigger.Manual,
                        ),
                    ) as AnalysisResult.Completion
                for (name in listOf("m00", "m33", "transposed", "transformPoint", "transformDirection", "times", "get")) {
                    assertTrue(completion.items.any { it.insertText == name }, completion.items.toString())
                }
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
            val offset = source.indexOf("42")

            assertTrue(
                active.diagnostics.any {
                    it.severity == EditorDiagnosticSeverity.Error && it.range == EditorRange(offset, offset + 2)
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
