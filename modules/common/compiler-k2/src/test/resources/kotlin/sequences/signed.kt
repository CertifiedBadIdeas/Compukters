import kotlin.collections.*
import kotlin.sequences.*

fun main() {
    checkBooleanTerminals(true)
    checkNullSelection(true)
    checkGenericSequence(true)
    checkByteTerminals(2.toByte())
    checkNullSelection(2.toByte())
    checkGenericSequence(2.toByte())
    checkShortTerminals(3.toShort())
    checkNullSelection(3.toShort())
    checkGenericSequence(3.toShort())
    checkCharTerminals('x')
    checkNullSelection('x')
    checkGenericSequence('x')
    checkLongTerminals(4L)
    checkNullSelection(4L)
    checkGenericSequence(4L)
    checkFloatTerminals(1.5f)
    checkNullSelection(1.5f)
    checkGenericSequence(1.5f)

    check(listOf(true).asSequence().filter { it }.map { !it }.toList()[0] == false)
    check(listOf<Byte>(2).asSequence().filter { true }.map { it }.toList()[0] == 2.toByte())
    check(listOf<Short>(3).asSequence().filter { true }.map { it }.toList()[0] == 3.toShort())
    check(listOf('x').asSequence().filter { true }.map { it }.toList()[0] == 'x')
    check(listOf(4L).asSequence().filter { true }.map { it }.toList()[0] == 4L)
    check(listOf(1.5f).asSequence().filter { true }.map { it }.toList()[0] == 1.5f)
    val bytes = listOf<Byte>(-128, 127).asSequence().filter { true }.toList()
    check(bytes[0] == (-128).toByte() && bytes[1] == 127.toByte())
    val shorts = listOf<Short>(-32768, 32767).asSequence().filter { true }.toList()
    check(shorts[0] == (-32768).toShort() && shorts[1] == 32767.toShort())
    val floats = listOf(Float.NaN, -0.0f).asSequence().filter { true }.toList()
    check(floats[0] != floats[0] && 1.0f / floats[1] == Float.NEGATIVE_INFINITY)
    val selectedFloats = listOf(Float.NaN, -0.0f).asSequence().mapNotNull { it }.toList()
    check(selectedFloats[0] != selectedFloats[0] && 1.0f / selectedFloats[1] == Float.NEGATIVE_INFINITY)
    println("sequences signed ok")
}

fun checkBooleanTerminals(value: Boolean) {
    val sequence = listOf<Boolean>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Boolean, Boolean>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkByteTerminals(value: Byte) {
    val sequence = listOf<Byte>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Byte, Byte>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkShortTerminals(value: Short) {
    val sequence = listOf<Short>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Short, Short>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkCharTerminals(value: Char) {
    val sequence = listOf<Char>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Char, Char>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkLongTerminals(value: Long) {
    val sequence = listOf<Long>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Long, Long>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkFloatTerminals(value: Float) {
    val sequence = listOf<Float>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Float, Float>(value) { acc, element -> check(acc == value); element } == value)
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
