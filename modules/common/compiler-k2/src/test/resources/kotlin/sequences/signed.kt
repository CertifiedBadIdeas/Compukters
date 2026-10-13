import kotlin.collections.*
import kotlin.sequences.*

fun main() {
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
