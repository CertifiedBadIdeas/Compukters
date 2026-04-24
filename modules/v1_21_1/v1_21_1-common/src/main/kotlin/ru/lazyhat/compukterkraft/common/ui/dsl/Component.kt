package ru.lazyhat.compukterkraft.common.ui.dsl

import net.minecraft.network.chat.Component
import ru.lazyhat.compukterkraft.core.ui.foundation.Value

class TranslatableValueConstant(
    key: String,
) : Value<String> {
    override val value: String = Component.translatable(key).string
}

fun interface TranslatableValueExpression : Value<String> {
    override val value: String
        get() = Component.translatable(evaluate()).string

    fun evaluate(): String
}

fun translatable(block: () -> String): TranslatableValueExpression = TranslatableValueExpression(block)

fun translatable(key: String): Value<String> = TranslatableValueConstant(key)
