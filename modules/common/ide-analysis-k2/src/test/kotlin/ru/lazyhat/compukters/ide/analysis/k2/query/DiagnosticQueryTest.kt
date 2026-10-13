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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticQueryTest {
    @Test
    fun `shipped boiler controller resolves through native IDE diagnostics`() {
        val controller = Files.readString(Path.of(checkNotNull(System.getProperty("compukters.test.boilerController"))))
        val source =
            """
            import boiler.control.BoilerControl
            import boiler.control.Phase
            import boiler.control.unavailableSample
            fun main() {
                val control = BoilerControl()
                val sample = unavailableSample()
                control.start(sample)
                check(!sample.available && control.phase == Phase.IDLE)
            }
            """.trimIndent()
        for (attachedSources in listOf(false, true)) {
            K2QueryFixture
                .sourceWithGuestApi(
                    attachedSources,
                    "control/BoilerControl.kt" to controller,
                    "main.kt" to source,
                ).use { fixture ->
                    for (path in listOf("control/BoilerControl.kt", "main.kt")) {
                        val result = fixture.execute(fixture.presentation(path)) as AnalysisResult.Presentation
                        val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                        assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
                    }
                }
        }
    }

    @Test
    fun `enum entries resolve typed indexing iteration and platform enums`() {
        val source =
            """
            import compukter.redstone.RedstonePort
            import kotlin.enums.EnumEntries
            enum class Choice { FIRST, SECOND }
            enum class Empty
            fun main() {
                val values: EnumEntries<Choice> = Choice.entries
                val first: Choice = values[0]
                for (entry in values) check(entry in values)
                check(first === Choice.FIRST)
                val name: String = first.name
                val index: Int = first.ordinal
                val generic: Enum<*> = first
                check(generic.name == name && generic.ordinal == index)
                check(Empty.entries.isEmpty())
                val sides: EnumEntries<RedstonePort.Side> = RedstonePort.Side.entries
                check(sides[0] === RedstonePort.Side.ALL)
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
    fun `classic contracts and platform construction effects resolve with source and metadata inputs`() {
        val source =
            """
            @file:OptIn(kotlin.contracts.ExperimentalContracts::class)
            import kotlin.contracts.*
            import compukter.display.*
            import kotlin.math.*
            fun text(value: Any?): Boolean {
                contract { returns(true) implies (value is String) }
                return value is String
            }
            fun main() {
                val name: String
                run { name = "panel" }
                check(name.length == 5)
                val widget: UiSlider
                val ui = displayUi(Rect2i(0 xy 0, 80 by 80)) {
                    column { row { widget = slider(20 by 10) } }
                }
                widget.value = 0.5f
                val length: Int
                buildString { length = 3; append("abc") }
                check(length == 3)
                val value: Any? = "text"
                if (text(value)) check(value.length == 4)
                val required: String? = "ok"
                require(required != null)
                check(required.length == 2)
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
    fun `nullable callbacks resolve with source and metadata inputs`() {
        val source =
            """
            class Controller {
                var onUpdate: (() -> Unit)? = null
                fun update() { onUpdate?.invoke() }
            }
            class Holder<T>(var callback: ((T) -> T)? = null)
            fun <T> make(value: T): (() -> T)? {
                fun read(): T = value
                return ::read
            }
            fun main() {
                val controller = Controller()
                controller.onUpdate = { }
                controller.update()
                controller.onUpdate = null
                val holder = Holder<Int>()
                holder.callback = { it + 1 }
                check(holder.callback?.invoke(2) == 3)
                holder.callback = null
                val read = make(7)
                if (read != null) check(read() == 7)
                check((read ?: { 0 })() == 7)
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
    fun `function fields resolve with source and metadata inputs`() {
        val source =
            """
            class Counter(val next: () -> Int)
            class Holder<T>(var callback: (T) -> T)
            fun counter(): Counter {
                var count = 0
                fun next(): Int { count += 1; return count }
                return Counter(::next)
            }
            fun main() {
                val value = counter()
                val holder = Holder<Int> { it + value.next() }
                holder.callback = { it * 2 }
                check(holder.callback(3) == 6)
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
    fun `generic methods resolve with source and metadata inputs`() {
        val source =
            """
            class Box<T>(var value: T) {
                fun <R> map(transform: (T) -> R): Box<R> = Box(transform(value))
                fun <R> reader(seed: R, take: () -> R = { seed }): () -> R = take
                fun <R> cell(seed: R, next: R): () -> R {
                    var local = seed
                    fun take(): R { val saved = local; local = next; return saved }
                    return ::take
                }
            }
            fun main() {
                val box = Box(3)
                check(box.map { it.toLong() }.value == 3L)
                check(box.reader<Int?>(null)() == null)
                check(box.cell("first", "next")() == "first")
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
    fun `generic initialization captures resolve with source and metadata inputs`() {
        val source =
            """
            class Holder<T>(initial: T, next: T) {
                var value: T = initial
                val snapshot: () -> T = { initial }
                val live: () -> T = { value }
                var take: () -> T = { initial }
                init {
                    var current = initial
                    fun <R> identity(other: R): R = other
                    fun advance(): T { val saved = current; current = identity(next); return saved }
                    take = ::advance
                }
            }
            fun main() {
                val ints = Holder(3, 7)
                check(ints.snapshot() == 3 && ints.take() == 3)
                ints.value = 9
                check(ints.live() == 9 && ints.take() == 7)
                val nullable = Holder<Int?>(null, 2)
                check(nullable.take() == null && nullable.take() == 2)
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
    fun `lazy sequences resolve with source and metadata inputs`() {
        val source =
            """
            import kotlin.collections.*
            import kotlin.sequences.*
            fun main() {
                val values: Sequence<Int?> = listOf<Int?>(null, 2, 3).asSequence()
                val lazy = values.filter { it != null }.filterNot { it == 3 }.map { it }.take(1)
                val result: List<Int?> = lazy.toList()
                check(result[0] == 2 && lazy.firstOrNull { it != null } == 2)
                lazy.forEach { check(it == 2) }
                val sum: Long = lazy.fold(0L) { acc, value -> acc + (value ?: 0).toLong() }
                check(sum == 2L && lazy.count() == 1 && lazy.count { it != null } == 1)
                check(lazy.any() && !lazy.none())
                check(lazy.any { it == 2 } && lazy.all { it != null } && lazy.none { it == 3 })
                val iterator = lazy.iterator()
                check(iterator.hasNext() && iterator.next() == 2)
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
    fun `generic class captures resolve with source and metadata inputs`() {
        val source =
            """
            class Holder<T>(var value: T) {
                fun reader(): () -> T {
                    fun read(): T = value
                    return ::read
                }
                fun cell(seed: T): () -> T {
                    var local = seed
                    return { val saved = local; local = value; saved }
                }
            }
            fun main() {
                val ints = Holder(3)
                val read = ints.reader()
                ints.value = 7
                check(read() == 7)
                val wide = Holder(3L).cell(5L)
                check(wide() == 5L)
                val nullable = Holder<Int?>(null).reader()
                check(nullable() == null)
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
    fun `generic local functions resolve with source and metadata inputs`() {
        val source =
            """
            fun <T> make(value: T): () -> T {
                var current = value
                fun <R> keep(other: R, chosen: T = current): T { current = chosen; return current }
                fun read(): T = keep(1)
                return ::read
            }
            fun main() {
                fun <T> twice(value: T, transform: (T) -> T): T = transform(transform(value))
                val id: (Int) -> Int = ::twiceInt
                check(twice(make(3)()) { id(it) } == 5)
            }
            fun twiceInt(value: Int): Int = value + 1
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
    fun `captured local functions resolve with source and metadata inputs`() {
        val source =
            """
            fun counter(seed: Int): () -> Int {
                var count = seed
                fun next(step: Int = 1): Int { count += step; return count }
                fun read(): Int = count
                next(read())
                return ::next
            }
            fun main() {
                fun sum(depth: Int): Int = if (depth == 0) 1 else 1 + sum(depth - 1)
                val next = counter(sum(2))
                check(next() == 7)
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
    fun `default adapted function references resolve with source and metadata inputs`() {
        val source =
            """
            import kotlin.random.Random
            fun roll(sides: Int = 6): Int = sides
            open class Base { open fun read(value: Int = 7): Int = value }
            class Derived : Base() { override fun read(value: Int): Int = value + 1 }
            fun main() {
                val draw: () -> Int = ::roll
                val member: () -> Int = Derived()::read
                val unbound: (Base) -> Int = Base::read
                val fill: (ByteArray) -> ByteArray = Random(0)::nextBytes
                check(draw() == 6 && member() == 8 && unbound(Derived()) == 8)
                fill(ByteArray(3))
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
    fun `function expression defaults resolve with source and metadata inputs`() {
        val source =
            """
            import kotlin.random.Random
            open class Base(val number: Int) { open fun read(value: Int = number): Int = value }
            class Derived : Base(7) { override fun read(value: Int): Int = value + 1 }
            fun <T> choose(value: T, fallback: T = value): T = fallback
            fun Int.offset(delta: Int = this + 1): Int = this + delta
            fun roll(sides: Int = 6, random: Random = Random.Default): Int = random.nextInt(1, sides + 1)
            fun main() { check(Derived().read() == 8); check(choose(2) == 2); check(3.offset() == 7); roll(random = Random(42)) }
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
    fun `text builders iteration sizing and numeric parsing resolve with source and metadata inputs`() {
        val source =
            """
            fun main() {
                val builder: CharSequence = StringBuilder(8).append('x').append("text")
                val text = buildString { append(builder); appendLine(42) }
                val sequence: CharSequence = text
                val trimmed: CharSequence = builder.trim().take(2).padEnd(4).removePrefix(builder)
                val stringTrimmed: String = text.trim().take(2).padEnd(4).removePrefix(builder)
                val parts: List<String> = builder.split(':')
                val lines: List<String> = builder.lines()
                val repeated: String = builder.repeat(2)
                val contains: Boolean = builder.contains(sequence)
                val position: Int = builder.indexOf("text")
                compukter.terminal.Terminal.write(builder)
                compukter.terminal.Terminal.write('x')
                compukter.terminal.Terminal.write(charArrayOf('x'))
                compukter.terminal.Terminal.write(charArrayOf('x'), 0, 1)
                compukter.terminal.Terminal.writeAt(kotlin.math.Vector2i(0, 0), builder)
                compukter.terminal.Terminal.writeAt(kotlin.math.Vector2i(0, 0), 'x')
                compukter.terminal.Terminal.writeAt(kotlin.math.Vector2i(0, 0), charArrayOf('x'), 0, 1)
                sequence.subSequence(0, 1)
                for (unit in sequence) { println(unit) }
                for (char in text) { println(char) }
                val iterator: kotlin.collections.CharIterator = text.iterator()
                iterator.nextChar()
                val resized = text.take(3).dropLast(1).padEnd(5, '.').repeat(2)
                val number: Long? = "7fff".toLongOrNull(16)
                val unsigned: ULong = "ff".toULong(16)
                try { resized.toInt() } catch (failure: NumberFormatException) { println(number.toString()) }
            }
            """.trimIndent()
        for (attachedSources in listOf(false, true)) {
            K2QueryFixture
                .sourceWithGuestApi(
                    attachedSources,
                    "main.kt" to source,
                    limits = AnalysisLimits(sourceFiles = 1),
                    includeAddonBundle = false,
                ).use { fixture ->
                    val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                    val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                    assertTrue(active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
                }
        }
    }

    @Test
    fun `CharSequence implementations must provide abstract methods`() {
        val source = "class Incomplete : CharSequence { override val length: Int get() = 0 }"
        for (attachedSources in listOf(false, true)) {
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
                assertTrue(active.diagnostics.any { it.severity == EditorDiagnosticSeverity.Error }, active.diagnostics.toString())
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
                import compukter.display.DisplayWidgets
                import compukter.display.DisplayHistory
                import compukter.display.frame
                fun main() {
                    val point: Position2i = 10 xy 20
                    val size: Size2i = 80 by 24
                    val bounds: Rect2i = point sized size
                    val fractional: Vector2f = (3f xy 4f).normalized()
                    val volume: Position3i = Vector3i(1, 2, 3)
                    val screen = GraphicalDisplay.named("panel")
                    val click = screen.pollClick()
                    screen.frame {
                        val widgets = DisplayWidgets(this)
                        widgets.button(bounds, "Control", click, true)
                        widgets.gauge(bounds, 50f, 0f, 100f)
                        val history = DisplayHistory(32)
                        history.add(10f)
                        widgets.chart(bounds, history, 0f, 100f)
                    }
                    if (click != null) screen.pixel(click.position, 0xFFFFFF)
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
