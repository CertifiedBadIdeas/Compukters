import kotlin.collections.*
import kotlin.sequences.*

fun main() {
    checkDoubleTerminals(2.5)
    checkGenericSequence(2.5)
    checkUByteTerminals(5.toUByte())
    checkGenericSequence(5.toUByte())
    checkUShortTerminals(6.toUShort())
    checkGenericSequence(6.toUShort())
    checkUIntTerminals(7u)
    checkGenericSequence(7u)
    checkULongTerminals(8uL)
    checkGenericSequence(8uL)

    check(listOf(2.5).asSequence().filter { true }.map { it }.toList()[0] == 2.5)
    check(listOf<UByte>(5u).asSequence().filter { true }.map { it }.toList()[0] == 5.toUByte())
    check(listOf<UShort>(6u).asSequence().filter { true }.map { it }.toList()[0] == 6.toUShort())
    check(listOf(7u).asSequence().filter { true }.map { it }.toList()[0] == 7u)
    check(listOf(8uL).asSequence().filter { true }.map { it }.toList()[0] == 8uL)
    check(listOf<UByte>(255u).asSequence().filter { true }.firstOrNull() == 255.toUByte())
    check(listOf<UShort>(65535u).asSequence().filter { true }.firstOrNull() == 65535.toUShort())
    check(listOf(UInt.MAX_VALUE).asSequence().filter { true }.firstOrNull() == UInt.MAX_VALUE)
    check(listOf(ULong.MAX_VALUE).asSequence().filter { true }.firstOrNull() == ULong.MAX_VALUE)
    val doubles = listOf(Double.NaN, -0.0).asSequence().filter { true }.toList()
    check(doubles[0] != doubles[0] && 1.0 / doubles[1] == Double.NEGATIVE_INFINITY)
    println("sequences unsigned ok")
}

fun checkDoubleTerminals(value: Double) {
    val sequence = listOf<Double>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<Double, Double>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkUByteTerminals(value: UByte) {
    val sequence = listOf<UByte>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<UByte, UByte>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkUShortTerminals(value: UShort) {
    val sequence = listOf<UShort>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<UShort, UShort>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkUIntTerminals(value: UInt) {
    val sequence = listOf<UInt>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<UInt, UInt>(value) { acc, element -> check(acc == value); element } == value)
    check(sequence.count() == 1 && sequence.count { it == value } == 1)
    check(sequence.any() && !sequence.none())
    check(sequence.any { it == value } && sequence.all { it == value } && sequence.none { it != value })
}

fun checkULongTerminals(value: ULong) {
    val sequence = listOf<ULong>(value).asSequence().filter { true }
    var visits = 0
    sequence.forEach { check(it == value); visits += 1 }
    check(visits == 1)
    check(sequence.fold<ULong, ULong>(value) { acc, element -> check(acc == value); element } == value)
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
