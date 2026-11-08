interface Producer<out T> { fun get(): T }
class Produced<T>(private val value: T) : Producer<T> { override fun get(): T = value }
class Rank(val value: Int) : Comparable<Rank> {
    override fun compareTo(other: Rank): Int = value.compareTo(other.value)
}
class GenericRank<T : Comparable<T>>(val value: T) : Comparable<GenericRank<T>> {
    override fun compareTo(other: GenericRank<T>): Int = value.compareTo(other.value)
}
class Universal : Comparable<Any> { override fun compareTo(other: Any): Int = 7 }
class CustomNumber(val value: Int) : Number() {
    override fun toByte(): Byte = value.toByte()
    override fun toShort(): Short = value.toShort()
    override fun toInt(): Int = value
    override fun toLong(): Long = value.toLong()
    override fun toFloat(): Float = value.toFloat()
    override fun toDouble(): Double = value.toDouble()
}
value class Score(val value: Int) : Comparable<Score> {
    override fun compareTo(other: Score): Int = value.compareTo(other.value)
}
enum class Order { FIRST, SECOND }
enum class OtherOrder { FIRST, SECOND }
fun <T : Comparable<T>> compare(a: T, b: T): Int = a.compareTo(b)
class RankProbe {
    var reads: Int = 0
    fun read(): Rank { reads++; return Rank(3) }
}
fun acceptsNullableNumber(value: Any?): Boolean = value is Number?
fun rejectsNullableNumber(value: Any?): Boolean = value !is Number?
fun numeric(n: Number) {
    check(n.toByte() == 7.toByte())
    check(n.toShort() == 7.toShort())
    check(n.toInt() == 7)
    check(n.toLong() == 7L)
    check(n.toFloat() == 7.0f)
    check(n.toDouble() == 7.0)
}
fun main() {
    numeric(7.toByte()); numeric(7.toShort()); numeric(7); numeric(7L)
    numeric(7.0f); numeric(7.0); numeric(CustomNumber(7))
    val values = listOf<Number>(1, 2L, 3.5f, 4.5)
    var total = 0.0
    for (value in values) total += value.toDouble()
    check(total == 11.0)
    val inferred = listOf(1, 2.5)
    var inferredTotal = 0.0
    for (value in inferred) inferredTotal += value.toDouble()
    check(inferredTotal == 3.5)
    val numbers = arrayOf<Number>(3, 4.5)
    check(numbers[1].toDouble() == 4.5)
    val nullable: Number? = 9
    check(nullable?.toInt() == 9)
    val absent: Number? = null
    check(absent?.toDouble() == null)
    check(acceptsNullableNumber(null) && acceptsNullableNumber(1))
    check(!acceptsNullableNumber("text"))
    check(!rejectsNullableNumber(null) && !rejectsNullableNumber(1))
    check(rejectsNullableNumber("text"))
    val dynamic: Any = 8
    check(dynamic is Number && dynamic is Comparable<*>)
    check((dynamic as Number).toDouble() == 8.0)
    check(("text" as Any) !is Number)
    check((1u as Any) !is Number)
    val original = Produced(9)
    val wide: Producer<Number> = original
    val comparable: Producer<Comparable<*>> = original
    check(wide === original && comparable === original)
    check(wide.get().toDouble() == 9.0)
    check(comparable.get() is Int)
    val nullableSource: Producer<Int?> = Produced(null)
    val nullableWide: Producer<Number?> = nullableSource
    check(nullableWide.get() == null)
    val narrow: Number = 258
    check(narrow.toByte() == 2.toByte())
    val notANumber: Number = Double.NaN
    check(notANumber.toInt() == 0)
    val infinity: Number = Double.POSITIVE_INFINITY
    check(infinity.toLong() == Long.MAX_VALUE)
    val negativeInfinity: Number = Double.NEGATIVE_INFINITY
    check(negativeInfinity.toInt() == Int.MIN_VALUE)
    val negativeZero: Number = -0.0
    check(1.0 / negativeZero.toDouble() == Double.NEGATIVE_INFINITY)
    val directNumber = 3 as Number
    check(directNumber.toDouble() == 3.0)
    val directComparable = 3 as Comparable<Int>
    check(directComparable.compareTo(4) < 0)
    val directNullableNumber = 3 as Number?
    check(directNullableNumber?.toInt() == 3)
    val intOrder: Comparable<Int> = 3
    check(intOrder.compareTo(4) < 0 && intOrder.compareTo(3) == 0)
    val longOrder: Comparable<Long> = Long.MIN_VALUE
    check(longOrder.compareTo(Long.MAX_VALUE) < 0)
    val byteOrder: Comparable<Byte> = (-1).toByte()
    check(byteOrder.compareTo(1.toByte()) < 0)
    val shortOrder: Comparable<Short> = (-1).toShort()
    check(shortOrder.compareTo(1.toShort()) < 0)
    val charOrder: Comparable<Char> = 'λ'
    check(charOrder.compareTo('a') > 0)
    val boolOrder: Comparable<Boolean> = false
    check(boolOrder.compareTo(true) < 0 && boolOrder.compareTo(false) == 0)
    val uintOrder: Comparable<UInt> = UInt.MAX_VALUE
    check(uintOrder.compareTo(0u) > 0)
    val ulongOrder: Comparable<ULong> = ULong.MAX_VALUE
    check(ulongOrder.compareTo(0uL) > 0)
    val ubyteOrder: Comparable<UByte> = 255u.toUByte()
    check(ubyteOrder.compareTo(1u.toUByte()) > 0)
    val ushortOrder: Comparable<UShort> = 65535u.toUShort()
    check(ushortOrder.compareTo(1u.toUShort()) > 0)
    val doubleOrder: Comparable<Double> = -0.0
    check(doubleOrder.compareTo(0.0) < 0 && doubleOrder.compareTo(-0.0) == 0)
    val nanOrder: Comparable<Double> = Double.NaN
    check(nanOrder.compareTo(Double.POSITIVE_INFINITY) > 0 && nanOrder.compareTo(Double.NaN) == 0)
    val floatOrder: Comparable<Float> = -0.0f
    check(floatOrder.compareTo(0.0f) < 0 && floatOrder.compareTo(-0.0f) == 0)
    val floatNanOrder: Comparable<Float> = Float.NaN
    check(floatNanOrder.compareTo(Float.POSITIVE_INFINITY) > 0 && floatNanOrder.compareTo(Float.NaN) == 0)
    val text: Comparable<String> = "abc"
    check(text.compareTo("abd") < 0 && text.compareTo("ab") > 0 && text.compareTo("abc") == 0)
    val probe = RankProbe()
    check(probe.read().compareTo(Rank(4)) < 0)
    check(probe.reads == 1)
    val rank = Rank(3)
    val ranked: Comparable<Rank> = rank
    check(ranked.compareTo(Rank(4)) < 0 && ranked.compareTo(rank) == 0)
    val generic: Comparable<GenericRank<Int>> = GenericRank(5)
    check(generic.compareTo(GenericRank(6)) < 0)
    val contravariant: Comparable<Rank> = Universal()
    check(contravariant.compareTo(rank) == 7)
    val score: Comparable<Score> = Score(3)
    check(score.compareTo(Score(4)) < 0)
    val order: Comparable<Order> = Order.FIRST
    check(order.compareTo(Order.SECOND) < 0)
    val wrongEnum = (Order.FIRST as Any) as Comparable<OtherOrder>
    try { wrongEnum.compareTo(OtherOrder.SECOND); error("missing enum owner check") }
    catch (expected: ClassCastException) {}
    check(compare(3, 4) < 0 && compare(rank, Rank(4)) < 0)
    val wrong = (3 as Any) as Comparable<String>
    try { wrong.compareTo("bad"); error("missing checked comparison cast") }
    catch (expected: ClassCastException) {}
    println("primitive supertypes ok")
}
