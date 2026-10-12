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
    println("contracts ok")
}
