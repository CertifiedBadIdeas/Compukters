package ru.lazyhat.compukterkraft.core.ui.foundation

fun interface ValueExpression<T> {
    fun evaluate(): T
}

fun <T> expr(block: () -> T): ValueExpression<T> = ValueExpression(block)
