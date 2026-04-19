package ru.lazyhat.compukterkraft.core.ui.foundation

fun interface UiExpression<T> {
    fun evaluate(): T
}

fun <T> expr(block: () -> T): UiExpression<T> = UiExpression(block)

fun textExpr(block: () -> String): UiExpression<String> = expr(block)
