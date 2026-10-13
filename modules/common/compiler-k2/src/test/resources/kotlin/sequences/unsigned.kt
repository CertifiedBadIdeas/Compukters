import kotlin.collections.*
import kotlin.sequences.*

fun main() {
    check(listOf(2.5).asSequence().filter { true }.map { it }.toList()[0] == 2.5)
    check(listOf<UByte>(5u).asSequence().filter { true }.map { it }.toList()[0] == 5.toUByte())
    check(listOf<UShort>(6u).asSequence().filter { true }.map { it }.toList()[0] == 6.toUShort())
    check(listOf(7u).asSequence().filter { true }.map { it }.toList()[0] == 7u)
    check(listOf(8uL).asSequence().filter { true }.map { it }.toList()[0] == 8uL)
    println("sequences unsigned ok")
}
