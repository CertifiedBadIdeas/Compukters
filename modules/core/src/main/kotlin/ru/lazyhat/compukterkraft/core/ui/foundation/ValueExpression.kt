package ru.lazyhat.compukterkraft.core.ui.foundation

interface Value<T> {
    val value: T
}

@JvmInline
value class ValueConstant(
    override val value: String,
) : Value<String>

private fun interface ValueExpression<T> : Value<T> {
    override val value: T
        get() = evaluate()

    fun evaluate(): T
}

fun value(string: String): Value<String> = ValueConstant(string)

fun <T> value(block: () -> T): Value<T> = ValueExpression(block)
