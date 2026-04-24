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

fun value(string: String): Value<String> = ValueConstant(string)

fun <T> value(block: () -> T): Value<T> = ValueExpression(block)
