import kotlin.collections.*
import kotlin.sequences.*

fun main() {
    check(listOf(true).asSequence().filter { it }.map { !it }.toList()[0] == false)
    check(listOf<Byte>(2).asSequence().filter { true }.map { it }.toList()[0] == 2.toByte())
    check(listOf<Short>(3).asSequence().filter { true }.map { it }.toList()[0] == 3.toShort())
    check(listOf('x').asSequence().filter { true }.map { it }.toList()[0] == 'x')
    check(listOf(4L).asSequence().filter { true }.map { it }.toList()[0] == 4L)
    check(listOf(1.5f).asSequence().filter { true }.map { it }.toList()[0] == 1.5f)
    println("sequences signed ok")
}
