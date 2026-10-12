import kotlin.contracts.*

@OptIn(ExperimentalContracts::class)
fun once(block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    block()
}

@OptIn(ExperimentalContracts::class)
fun twice(block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_LEAST_ONCE) }
    block()
    block()
}

@OptIn(ExperimentalContracts::class)
fun maybe(enabled: Boolean, block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    if (enabled) block()
}

@OptIn(ExperimentalContracts::class)
fun inPlace(block: () -> Unit) {
    contract { callsInPlace(block) }
    block()
}

@OptIn(ExperimentalContracts::class)
fun isText(value: Any?): Boolean {
    contract { returns(true) implies (value is String); returns(false) implies (value !is String) }
    return value is String
}

@OptIn(ExperimentalContracts::class)
fun needText(value: String?) {
    contract { returns() implies (value != null) }
    if (value == null) throw IllegalArgumentException("missing")
}

@OptIn(ExperimentalContracts::class)
fun present(value: String?): String? {
    contract { returnsNotNull() implies (value != null) }
    return value
}

@OptIn(ExperimentalContracts::class)
fun absent(value: String?): Boolean? {
    contract { returns(null) implies (value == null) }
    return if (value == null) null else false
}

class Box(val value: Int)
value class PairValue(val x: Int, val y: Int)

fun main() {
    val number: Int
    once { number = 7 }
    check(number == 7)
    val text: String
    once { text = "text" }
    check(text.length == 4)
    var updated: String
    twice { updated = "twice" }
    check(updated.length == 5)
    val scalar: Long
    scalar = 9223372036854775807L
    check(scalar == 9223372036854775807L)
    val boxed: Box
    once { boxed = Box(9) }
    check(boxed.value == 9)
    val pair: PairValue
    once { pair = PairValue(3, 4) }
    check(pair.x + pair.y == 7)
    val direct: Box
    direct = Box(12)
    val readDirect = { direct.value }
    check(readDirect() == 12)
    var replaceable: Box
    once { replaceable = Box(1) }
    val readReplaceable = { replaceable.value }
    replaceable = Box(2)
    check(readReplaceable() == 2)
    val nullableAssigned: Box?
    once { nullableAssigned = null }
    check(nullableAssigned == null)
    val array: IntArray
    once { array = intArrayOf(1, 2) }
    check(array[1] == 2)
    val narrow: Byte
    once { narrow = (-128).toByte() }
    check(narrow.toInt() == -128)
    val short: Short
    once { short = 32767.toShort() }
    check(short.toInt() == 32767)
    val unsignedByte: UByte
    once { unsignedByte = 255u }
    check(unsignedByte.toInt() == 255)
    val unsignedShort: UShort
    once { unsignedShort = 65535u }
    check(unsignedShort.toInt() == 65535)
    val unsigned: UInt
    once { unsigned = 4294967295u }
    check(unsigned == 4294967295u)
    val unsignedLong: ULong
    once { unsignedLong = 18446744073709551615uL }
    check(unsignedLong == 18446744073709551615uL)
    val flag: Boolean
    once { flag = true }
    check(flag)
    val character: Char
    once { character = '\uD800' }
    check(character.code == 0xD800)
    val floating: Float
    once { floating = -0f }
    check(1f / floating == Float.NEGATIVE_INFINITY)
    val wideFloating: Double
    once { wideFloating = Double.NaN }
    check(wideFloating != wideFloating)
    val branch: String
    if (number == 7) branch = "left" else branch = "right"
    check(branch.length == 4)
    var calls = 0
    maybe(false) { calls++ }
    maybe(true) { calls++ }
    inPlace { calls++ }
    check(calls == 2)
    val candidate: Any? = "value"
    if (isText(candidate)) check(candidate.length == 5)
    val nullable: String? = "ok"
    needText(nullable)
    check(nullable.length == 2)
    val other: String? = "abc"
    if (present(other) != null) check(other.length == 3)
    check(absent(null) == null && absent("x") == false)
    val scoped: String
    run { scoped = "scope" }
    check(scoped.length == 5)
    val receiverScoped: Int
    Box(13).run { receiverScoped = value }
    check(receiverScoped == 13)
    val transformed: Int
    Box(2).let { transformed = it.value }
    check(transformed == 2)
    val configured: Int
    Box(3).apply { configured = value }
    check(configured == 3)
    val observed: Int
    Box(4).also { observed = it.value }
    check(observed == 4)
    val insideWith: Int
    with(Box(5)) { insideWith = value }
    check(insideWith == 5)
    val filtered: Int
    Box(6).takeIf { filtered = it.value; true }
    check(filtered == 6)
    val rejected: Int
    Box(7).takeUnless { rejected = it.value; true }
    check(rejected == 7)
    val content: String
    val built = buildString { content = "built"; append(content) }
    check(built == content)
    val capacityValue: Int
    check(buildString(4) { capacityValue = 4; append("abcd") }.length == capacityValue)
    val unknown: Any? = "assertion"
    require(unknown is String)
    check(unknown.length == 9)
    val checked: String? = "checked"
    check(checked != null) { "missing" }
    check(checked.length == 7)
    val required: String? = "required"
    require(required != null) { "missing" }
    check(required.length == 8)
    val plainChecked: Any = Box(11)
    check(plainChecked is Box)
    check(plainChecked.value == 11)
    println("contracts ok")
}
