import kotlin.collections.*
import kotlin.sequences.*

class Probe : Iterable<Int> {
    var iterators = 0
    var reads = 0
    override fun iterator(): Iterator<Int> {
        iterators += 1
        return ProbeIterator(this)
    }
}
class ProbeIterator(private val owner: Probe) : Iterator<Int> {
    private var current = 0
    override fun hasNext(): Boolean = current < 8
    override fun next(): Int {
        if (!hasNext()) throw NoSuchElementException("Sequence iterator is exhausted")
        owner.reads += 1
        current += 1
        return current
    }
}
class Item(val value: Int)
fun findInline(): Int {
    listOf(1, 2).asSequence().firstOrNull { if (it == 2) return 9; false }
    return 0
}
fun main() {
    checkCustomCovariance()
    checkIntTerminals(1)
    checkNullSelection(1)
    checkGenericSequence(1)
    checkGenericTerminals(3)
    checkGenericSequence<Int?>(null)
    checkGenericSequence(Item(7))
    checkNullSelection(Item(7))
    check(genericTransform(genericForward(listOf(1, 2).asSequence(), 2)) { it.toLong() + 1L }.firstOrNull() == 3L)
    val converted = genericTransform(listOf<Int?>(null, 5).asSequence()) { it == null }.toList()
    check(converted[0] && !converted[1])
    check(genericChanging(1, 9).firstOrNull() == 9)
    check(genericChanging<Int?>(null, 4).firstOrNull() == 4)
    checkGenericTerminals<Int?>(null)
    checkGenericTerminals(Item(5))

    val source = Probe()
    var filtered = 0
    var mapped = 0
    var order = 0
    val chain = source.asSequence().filter {
        filtered += 1
        order = order * 10 + it
        it % 2 == 0
    }.map {
        mapped += 1
        order = order * 10 + 9
        it * 10
    }.take(2)
    check(source.iterators == 0 && filtered == 0 && mapped == 0)
    val a = chain.iterator()
    check(source.iterators == 1 && filtered == 0 && mapped == 0)
    check(a.hasNext() && a.hasNext() && filtered == 2 && mapped == 0)
    check(a.next() == 20 && mapped == 1)
    check(a.next() == 40 && !a.hasNext())
    check(filtered == 4 && source.reads == 4 && mapped == 2 && order == 129349)
    var exhausted = false
    try { a.next() } catch (e: NoSuchElementException) { exhausted = true }
    check(exhausted)
    val b = chain.iterator()
    val c = chain.iterator()
    check(b.next() == 20 && c.next() == 20 && b.next() == 40 && c.next() == 40)
    check(chain.toList()[1] == 40)
    val before = source.iterators
    val empty = chain.take(0)
    check(!empty.iterator().hasNext() && empty.firstOrNull() == null && empty.toList().size == 0)
    check(source.iterators == before)
    exhausted = false
    try { empty.iterator().next() } catch (e: NoSuchElementException) { exhausted = true }
    check(exhausted)
    var negative = false
    try { chain.take(-1) } catch (e: IllegalArgumentException) { negative = true }
    check(negative && source.iterators == before)

    val first = Probe()
    check(first.asSequence().filterNot { it < 3 }.firstOrNull() == 3 && first.reads == 3)
    check(first.asSequence().firstOrNull { it == 2 } == 2 && first.reads == 5)
    check(findInline() == 9)
    check(emptyList<Int>().asSequence().filter { true }.firstOrNull() == null)
    check(listOf(1).asSequence().filter { false }.toList().size == 0)
    val noMatch = listOf(1).asSequence().filter { false }.iterator()
    check(!noMatch.hasNext() && !noMatch.hasNext())
    exhausted = false
    try { noMatch.next() } catch (e: NoSuchElementException) { exhausted = true }
    check(exhausted)

    val nullable = listOf<Int?>(null, 7, null).asSequence().filter { true }.iterator()
    check(nullable.hasNext() && nullable.hasNext() && nullable.next() == null)
    check(nullable.next() == 7 && nullable.next() == null && !nullable.hasNext())
    val item = Item(3)
    check(listOf(item).asSequence().map { it }.filter { true }.firstOrNull() === item)
    val refs = listOf<Item?>(null, item).asSequence().filter { true }.toList()
    check(refs[0] == null && refs[1] === item)
    val nulls = listOf(1, 2).asSequence().map<Int, String?> { if (it == 1) null else "ok" }.toList()
    check(nulls[0] == null && nulls[1] == "ok")
    val mutable = ArrayList<Int>()
    mutable.add(1)
    val borrowed = mutable.asSequence()
    mutable.add(2)
    check(borrowed.toList().size == 2)

    var callbackFailed = false
    val badMap = listOf(1).asSequence().map {
        if (it == 1) throw IllegalStateException("map callback")
        it
    }
    check(badMap.iterator().hasNext())
    try { badMap.firstOrNull() } catch (e: IllegalStateException) { callbackFailed = true }
    check(callbackFailed)
    callbackFailed = false
    val badFilter = listOf(1).asSequence().filter {
        if (it == 1) throw IllegalStateException("filter callback")
        true
    }
    try { badFilter.iterator().hasNext() } catch (e: IllegalStateException) { callbackFailed = true }
    check(callbackFailed)
    // Retained captures observe subsequent changes, and are shared by independent traversals.
    var offset = 1
    val captured = listOf(2).asSequence().map { it + offset }
    offset = 4
    check(captured.firstOrNull() == 6)
    offset = 5
    check(captured.firstOrNull() == 7)
    testNullSelection()
    testTerminals()
    println("sequences ok")
}

fun terminalReturn(kind: Int): Int {
    val source = listOf(1, 2, 3).asSequence()
    when (kind) {
        0 -> source.forEach { if (it == 2) return 10 }
        1 -> source.fold(0) { sum, value -> if (value == 2) return 11; sum + value }
        2 -> source.count { if (it == 2) return 12; false }
        3 -> source.any { if (it == 2) return 13; false }
        4 -> source.all { if (it == 2) return 14; true }
        5 -> source.none { if (it == 2) return 15; false }
    }
    return -1
}
fun testTerminals() {
    val values = listOf(1, 2, 3).asSequence()
    var order = 0
    values.forEach { order = order * 10 + it }
    check(order == 123)
    check(values.fold(4L) { sum, value -> sum * 10L + value.toLong() } == 4123L)
    check(values.count() == 3 && values.count { it % 2 == 1 } == 2)
    check(values.any() && !values.none())
    check(values.any { it == 2 } && !values.any { it == 9 })
    check(values.all { it > 0 } && !values.all { it < 3 })
    check(values.none { it == 9 } && !values.none { it == 2 })
    val early = Probe()
    val sequence = early.asSequence()
    check(sequence.any() && !sequence.none() && early.reads == 0 && early.iterators == 2)
    check(sequence.any { it == 2 } && early.reads == 2)
    check(!sequence.all { it < 3 } && early.reads == 5)
    check(!sequence.none { it == 2 } && early.reads == 7)
    // hasNext on a filtering iterator may consume upstream, but map must remain deferred.
    var mapped = 0
    val filtered = Probe()
    val chain = filtered.asSequence().filter { it == 3 }.map { mapped += 1; it }
    check(chain.any() && filtered.reads == 3 && mapped == 0)
    check(!chain.none() && filtered.reads == 6 && mapped == 0)
    check(chain.take(1).count() == 1 && filtered.reads == 9 && mapped == 1)
    val empty = early.asSequence().take(0)
    val before = early.iterators
    empty.forEach { throw IllegalStateException("empty action") }
    check(empty.fold(9) { sum, value -> throw IllegalStateException("empty fold") } == 9)
    check(empty.count() == 0 && empty.count { throw IllegalStateException("empty count") } == 0)
    check(!empty.any() && empty.none())
    check(!empty.any { throw IllegalStateException("empty any") })
    check(empty.all { throw IllegalStateException("empty all") })
    check(empty.none { throw IllegalStateException("empty none") })
    check(early.iterators == before)
    val nullable = listOf<Int?>(null, 2, null).asSequence()
    check(nullable.count() == 3 && nullable.count { it == null } == 2)
    check(nullable.any { it == null } && !nullable.all { it != null } && !nullable.none { it == null })
    check(nullable.fold<Int?, Int?>(null) { acc, value -> value ?: acc } == 2)
    var nullVisits = 0
    nullable.forEach { if (it == null) nullVisits += 1 }
    check(nullVisits == 2)
    val item = Item(7)
    val refs = listOf<Item?>(null, item).asSequence()
    check(refs.fold<Item?, Item?>(null) { acc, value -> value ?: acc } === item)
    val seed = Item(8)
    check(emptyList<Item>().asSequence().fold(seed) { acc, value -> value } === seed)
    for (kind in 0..5) {
        check(terminalReturn(kind) == 10 + kind)
        var failed = false
        try {
            when (kind) {
                0 -> values.forEach { throw IllegalStateException("terminal") }
                1 -> values.fold(0) { acc, value -> throw IllegalStateException("terminal") }
                2 -> values.count { throw IllegalStateException("terminal") }
                3 -> values.any { throw IllegalStateException("terminal") }
                4 -> values.all { throw IllegalStateException("terminal") }
                5 -> values.none { throw IllegalStateException("terminal") }
            }
        } catch (e: IllegalStateException) { failed = true }
        check(failed)
    }
    var upstreamFailed = false
    try {
        values.map { if (it > 0) throw IllegalStateException("upstream"); it }.count()
    } catch (e: IllegalStateException) { upstreamFailed = true }
    check(upstreamFailed)
    var evaluations = 0
    val receiver = values.map { evaluations += 1; it }
    var initialEvaluations = 0
    check(receiver.fold(run { initialEvaluations += 1; 0 }) { acc, value -> acc + value } == 6)
    check(evaluations == 3 && initialEvaluations == 1)
}

fun checkIntTerminals(value: Int) {
    val sequence = listOf<Int>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Int, Int>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun <T> checkGenericTerminals(value: T) {
    val sequence = listOf<T>(value).asSequence()
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<T, T>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

class GenericSequencePredicate<T>(private val value: T) {
    fun accepts(element: T): Boolean = element == value
}
fun <T> genericSelect(source: Sequence<T>, value: T): Sequence<T> =
    source.filter { it == value }.filterNot { it != value }
fun <T> genericForward(source: Sequence<T>, value: T): Sequence<T> = genericSelect(source, value)
fun <T, R> genericTransform(source: Sequence<T>, transform: (T) -> R): Sequence<R> =
    source.map { transform(it) }
fun <T> genericPredicate(value: T): (T) -> Boolean = { it == value }
fun <T> genericBound(source: Sequence<T>, value: T): Sequence<T> =
    source.filter(GenericSequencePredicate<T>(value)::accepts)
fun <T> checkGenericSequence(value: T) {
    val source = listOf<T>(value, value).asSequence()
    var callbacks = 0
    val chain = genericTransform(genericForward(source, value)) { callbacks += 1; it }
    check(callbacks == 0)
    val a = chain.iterator()
    val b = chain.iterator()
    check(a.hasNext() && a.hasNext() && callbacks == 0)
    check(a.next() == value && callbacks == 1)
    check(b.next() == value && callbacks == 2)
    check(a.next() == value && !a.hasNext() && callbacks == 3)
    check(b.next() == value && !b.hasNext() && callbacks == 4)
    check(chain.take(1).count() == 1 && callbacks == 5)
    check(source.filter(genericPredicate(value)).count() == 2)
    check(genericBound(source, value).count() == 2)
}

fun <T> genericChanging(initial: T, replacement: T): Sequence<T> {
    var current = initial
    val result = listOf<T>(initial).asSequence().map { current }
    current = replacement
    return result
}

fun testNullSelection() {
    val source = Probe()
    var calls = 0
    var order = 0
    val chain: Sequence<Int> = source.asSequence().mapNotNull {
        calls += 1
        order = order * 10 + it
        if (it % 2 == 0) it * 10 else null
    }.take(2)
    check(source.iterators == 0 && calls == 0)
    val a = chain.iterator()
    val b = chain.iterator()
    check(source.iterators == 2 && calls == 0)
    check(a.hasNext() && a.hasNext() && calls == 2 && source.reads == 2)
    check(a.next() == 20 && calls == 2)
    check(a.next() == 40 && !a.hasNext() && calls == 4 && order == 1234)
    check(b.next() == 20 && b.next() == 40 && !b.hasNext() && calls == 8)
    val before = source.iterators
    check(chain.take(0).count() == 0 && source.iterators == before && calls == 8)
    var exhausted = false
    try { a.next() } catch (e: NoSuchElementException) { exhausted = true }
    check(exhausted)
    val early = Probe()
    check(early.asSequence().mapNotNull { if (it == 3) it else null }.firstOrNull() == 3 && early.reads == 3)
    var nullCalls = 0
    val allNull: Sequence<Int> = listOf(1, 2).asSequence().mapNotNull<Int, Int> { nullCalls += 1; null }
    val empty = allNull.iterator()
    check(!empty.hasNext() && !empty.hasNext() && nullCalls == 2)
    exhausted = false
    try { empty.next() } catch (e: NoSuchElementException) { exhausted = true }
    check(exhausted)
    check(emptyList<Int>().asSequence().mapNotNull { it }.count() == 0)
    check(listOf<Int?>(null, null).asSequence().filterNotNull().count() == 0)
    val item = Item(8)
    val refs: Sequence<Item> = listOf<Item?>(null, item, null).asSequence().filterNotNull()
    check(refs.firstOrNull() === item && refs.count() == 1)
    val strings: Sequence<String> = listOf<Int?>(null, 2, null).asSequence().mapNotNull { if (it == null) null else "ok" }
    check(strings.toList()[0] == "ok" && strings.count() == 1)
    var failed = false
    try {
        listOf(1).asSequence().mapNotNull { if (it == 1) throw IllegalStateException("transform"); it }.count()
    } catch (e: IllegalStateException) { failed = true }
    check(failed)
    failed = false
    try {
        listOf<Int?>(null).asSequence().map<Int?, Int?> { if (it == null) throw IllegalStateException("source"); it }.filterNotNull().count()
    } catch (e: IllegalStateException) { failed = true }
    check(failed)
    val parsed: List<Int> = listOf("1", "bad", "2").asSequence().mapNotNull { it.toIntOrNull() }.take(2).toList()
    check(parsed.size == 2 && parsed[0] == 1 && parsed[1] == 2)
    var enabled = false
    val retained = listOf(1, 2).asSequence().mapNotNull { if (enabled) it else null }
    enabled = true
    check(retained.count() == 2)
    enabled = false
    check(retained.count() == 0)
}

fun <T : Any> genericNotNull(source: Sequence<T?>): Sequence<T> = source.filterNotNull()
fun <T, R : Any> genericMapNotNull(source: Sequence<T>, transform: (T) -> R?): Sequence<R> =
    source.mapNotNull { transform(it) }
fun <T : Any> checkNullSelection(value: T) {
    checkCovariantViews(value)
    val source = listOf<T?>(null, value, null, value).asSequence()
    val filtered: Sequence<T> = genericNotNull(source)
    check(filtered.count() == 2 && filtered.firstOrNull() == value)
    var calls = 0
    val mapped: Sequence<T> = genericMapNotNull(source) { calls += 1; it }
    val iterator = mapped.iterator()
    check(iterator.hasNext() && iterator.hasNext() && calls == 2)
    check(iterator.next() == value && calls == 2)
    check(iterator.next() == value && calls == 4 && !iterator.hasNext())
    check(mapped.take(1).count() == 1 && calls == 6)
    var produced = 0
    val nonNullableSource: Sequence<T> = genericMapNotNull(listOf<T>(value, value).asSequence()) {
        produced += 1
        if (produced == 1) null else it
    }
    check(nonNullableSource.firstOrNull() == value && produced == 2)
}

interface Producer<out T> {
    fun produce(): T
}

class SingleProducer<T>(private val value: T) : Producer<T> {
    var reads = 0
    override fun produce(): T {
        reads += 1
        return value
    }
}

fun <T : Any> checkCovariantViews(value: T) {
    val source = SingleProducer<T>(value)
    val nullable: Producer<T?> = source
    val objects: Producer<Any> = source
    val optionalObjects: Producer<Any?> = source
    check(nullable === source && objects === source && optionalObjects === source)
    check(source.reads == 0)
    check(nullable.produce() == value && source.reads == 1)
    check(objects.produce() == value && source.reads == 2)
    check(optionalObjects.produce() == value && source.reads == 3)
    check(source.produce() == value && source.reads == 4)

    var transforms = 0
    val sequence: Sequence<T> = listOf<T>(value, value).asSequence().map { transforms += 1; it }.filter { true }.take(2)
    val nullableSequence: Sequence<T?> = sequence
    val objectSequence: Sequence<Any> = sequence
    val optionalSequence: Sequence<Any?> = sequence
    check(nullableSequence === sequence && objectSequence === sequence && optionalSequence === sequence)
    check(transforms == 0)
    val iterator: Iterator<T> = sequence.iterator()
    val nullableIterator: Iterator<T?> = iterator
    val objectIterator: Iterator<Any> = iterator
    check(nullableIterator === iterator && objectIterator === iterator)
    check(nullableIterator.hasNext() && nullableIterator.hasNext() && transforms == 1)
    check(objectIterator.next() == value && transforms == 1)
    check(nullableIterator.next() == value && transforms == 2)
    check(!iterator.hasNext())
    check(nullableSequence.firstOrNull() == value && transforms == 3)
    check(objectSequence.firstOrNull() == value && transforms == 4)
    check(optionalSequence.firstOrNull() == value && transforms == 5)
    check(nullableSequence.filterNotNull().firstOrNull() == value && transforms == 6)
}

value class CovariantValue(val code: Int)
class UnitProducer : Producer<Unit> {
    override fun produce() {}
}
value class ScalarProducer(val value: Int) : DerivedProducer<Int> {
    override fun produce(): Int = value
}
value class AggregateProducer(val value: Int, val tag: String) : Producer<Int> {
    override fun produce(): Int = value
}
value class GenericValueProducer<T>(val first: T, val second: T) : Producer<T> {
    override fun produce(): T = first
}

interface DerivedProducer<out T> : Producer<T>
object DefaultProducerProbe {
    var receiver: Any? = null
    var reads: Int = 0
}
interface DefaultProducer : Producer<Int> {
    override fun produce(): Int {
        check(this === DefaultProducerProbe.receiver)
        DefaultProducerProbe.reads++
        return 47
    }
}
value class DefaultValueProducer(val marker: Int) : DefaultProducer
interface PropertyProducer<out T> {
    val value: T
}
class PropertyValue<T>(override val value: T) : PropertyProducer<T>
value class InlinePropertyValue(override val value: Int) : PropertyProducer<Int>
interface KeyedProducer<K, out A, out B> {
    fun key(): K
    fun first(): A
    fun second(): B
}
class KeyedValues<K, A, B>(private val key: K, private val first: A, private val second: B) : KeyedProducer<K, A, B> {
    override fun key(): K = key
    override fun first(): A = first
    override fun second(): B = second
}
class InheritedProducer : DerivedProducer<Int> {
    override fun produce(): Int = 7
}
class CustomSequence(private val probe: Probe) : Sequence<Int> {
    override fun iterator(): Iterator<Int> = probe.iterator()
}
fun checkCustomCovariance() {
    val defaultProducer: Producer<Int> = DefaultValueProducer(1)
    DefaultProducerProbe.receiver = defaultProducer
    val defaultObjects: Producer<Any> = defaultProducer
    val defaultNullable: Producer<Int?> = defaultProducer
    check(defaultObjects === defaultProducer && defaultNullable === defaultProducer)
    check(defaultProducer.produce() == 47 && defaultObjects.produce() == 47 && defaultNullable.produce() == 47)
    check(DefaultProducerProbe.reads == 3)
    val property: PropertyProducer<Int> = PropertyValue<Int>(41)
    val propertyObjects: PropertyProducer<Any> = property
    val propertyNullable: PropertyProducer<Int?> = property
    check(propertyObjects === property && propertyNullable === property)
    check(propertyObjects.value == 41 && propertyNullable.value == 41)
    val inlineProperty: PropertyProducer<Int> = InlinePropertyValue(43)
    val inlinePropertyObjects: PropertyProducer<Any> = inlineProperty
    check(inlinePropertyObjects === inlineProperty && inlinePropertyObjects.value == 43)
    val keyed: KeyedProducer<String, Int, Long> = KeyedValues<String, Int, Long>("key", 31, 37L)
    val keyedObjects: KeyedProducer<String, Any, Any?> = keyed
    val keyedNullable: KeyedProducer<String, Int?, Long?> = keyed
    check(keyedObjects === keyed && keyedNullable === keyed)
    check(keyedObjects.key() == "key" && keyedObjects.first() == 31 && keyedObjects.second() == 37L)
    check(keyedNullable.first() == 31 && keyedNullable.second() == 37L)
    val scalarProducer: Producer<Int> = ScalarProducer(13)
    val scalarObjects: Producer<Any> = scalarProducer
    val scalarNullable: Producer<Int?> = scalarProducer
    check(scalarObjects === scalarProducer && scalarNullable === scalarProducer)
    check(scalarObjects.produce() == 13 && scalarNullable.produce() == 13 && scalarProducer.produce() == 13)
    val aggregateProducer: Producer<Int> = AggregateProducer(17, "tag")
    val aggregateObjects: Producer<Any> = aggregateProducer
    check(aggregateObjects === aggregateProducer && aggregateObjects.produce() == 17)
    val genericProducer: Producer<Int> = GenericValueProducer<Int>(19, 23)
    val genericObjects: Producer<Any> = genericProducer
    val genericNullable: Producer<Int?> = genericProducer
    check(genericObjects === genericProducer && genericNullable === genericProducer)
    check(genericObjects.produce() == 19 && genericNullable.produce() == 19)
    val unitSource: Producer<Unit> = UnitProducer()
    val unitView: Producer<Any> = unitSource
    check(unitView.produce() === unitView.produce())
    val valueSource: Producer<CovariantValue> = SingleProducer<CovariantValue>(CovariantValue(9))
    val nullableValue: Producer<CovariantValue?> = valueSource
    val objectValue: Producer<Any> = valueSource
    check(nullableValue.produce()?.code == 9 && (objectValue.produce() as CovariantValue).code == 9)
    val nestedSource: Producer<Sequence<Int>> = SingleProducer<Sequence<Int>>(listOf(6).asSequence())
    val nestedView: Producer<Sequence<Any>> = nestedSource
    check(nestedView.produce().firstOrNull() == 6)
    val nestedObjects: Producer<Any> = nestedSource
    check(nestedObjects.produce() === nestedSource.produce())
    val absentNested: Producer<Sequence<Int>?> = SingleProducer<Sequence<Int>?>(null)
    val absentNestedObjects: Producer<Any?> = absentNested
    check(absentNestedObjects.produce() == null)
    val inherited = InheritedProducer()
    val widened: Producer<Any> = inherited
    val widenedDerived: DerivedProducer<Int?> = inherited
    check(widened === inherited && widenedDerived === inherited)
    check(widened.produce() == 7 && widenedDerived.produce() == 7)
    val probe = Probe()
    val custom = CustomSequence(probe)
    val nullable: Sequence<Int?> = custom
    val objects: Sequence<Any> = custom
    check(nullable === custom && objects === custom && probe.iterators == 0 && probe.reads == 0)
    check(objects.take(2).count() == 2 && probe.iterators == 1 && probe.reads == 2)
    check(nullable.firstOrNull() == 1 && probe.iterators == 2 && probe.reads == 3)
    val nullProducer: Producer<Int?> = SingleProducer<Int?>(null)
    val nullableObjects: Producer<Any?> = nullProducer
    check(nullableObjects === nullProducer && nullableObjects.produce() == null)
    val item = Item(3)
    val refProducer: Producer<Item> = SingleProducer<Item>(item)
    val referenceObjects: Producer<Any> = refProducer
    check(referenceObjects.produce() === item)
    var failed = false
    val throwing: Sequence<Int> = listOf(1).asSequence().map { throw IllegalStateException("widened"); it }
    val throwingObjects: Sequence<Any> = throwing
    try { throwingObjects.firstOrNull() } catch (e: IllegalStateException) { failed = true }
    check(failed)
}
