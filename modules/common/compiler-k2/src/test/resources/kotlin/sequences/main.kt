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
    println("sequences ok")
}
