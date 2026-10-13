import kotlin.collections.*
import kotlin.sequences.*

fun main() {
    checkBooleanTerminals(true)
    checkGenericSequence(true)
    checkByteTerminals(2.toByte())
    checkGenericSequence(2.toByte())
    checkShortTerminals(3.toShort())
    checkGenericSequence(3.toShort())
    checkCharTerminals('x')
    checkGenericSequence('x')
    checkLongTerminals(4L)
    checkGenericSequence(4L)
    checkFloatTerminals(1.5f)
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
