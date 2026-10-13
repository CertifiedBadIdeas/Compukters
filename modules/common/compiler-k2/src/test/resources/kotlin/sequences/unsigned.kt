import kotlin.collections.*
import kotlin.sequences.*

fun main() {
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
