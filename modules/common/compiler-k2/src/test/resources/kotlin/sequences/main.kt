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
    checkIntTerminals(1)
    checkGenericTerminals(3)
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
