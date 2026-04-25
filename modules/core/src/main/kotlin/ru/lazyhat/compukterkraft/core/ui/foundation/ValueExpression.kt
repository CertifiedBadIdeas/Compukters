package ru.lazyhat.compukterkraft.core.ui.foundation

interface Value<T> {
    val value: T
}

@JvmInline
value class ValueConstant(
    override val value: String,
) : Value<String>

private class ValueExpression<T>(
    private val block: () -> T,
) : Value<T> {
    override val value: T
        get() = block()
}

private class TickValueExpression<T>(
    private val block: (Int) -> T,
) : Value<T> {
    override val value: T
        get() = block(TickContext.current)
}

fun value(string: String): Value<String> = ValueConstant(string)

fun <T> value(block: () -> T): Value<T> = ValueExpression(block)

/**
 * A [Value] whose computation receives the current monotonic UI tick. The tick
 * counter is incremented by [ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor.render]
 * before walking render ops. Useful for blink/animation primitives, e.g.:
 *
 *     val cursorVisible = tickValue { it / 6 % 2 == 0 }
 */
fun <T> tickValue(block: (Int) -> T): Value<T> = TickValueExpression(block)

/**
 * Holds the current monotonic UI tick. Updated by
 * [ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor.render] just
 * before rendering each frame. Read-only for DSL users; expose only through
 * [tickValue].
 */
object TickContext {
    var current: Int = 0
        internal set
}
