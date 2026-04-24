package ru.lazyhat.compukterkraft.core.ui.foundation

interface Value<T> {
    val value: T
}

@JvmInline
value class ValueConstant(
    override val value: String,
) : Value<String>

fun interface ValueExpression<T> : Value<T> {
    override val value: T
        get() = evaluate()

    fun evaluate(): T
}

fun value(string: String): ValueConstant = ValueConstant(string)

fun <T> value(block: () -> T): ValueExpression<T> = ValueExpression(block)
