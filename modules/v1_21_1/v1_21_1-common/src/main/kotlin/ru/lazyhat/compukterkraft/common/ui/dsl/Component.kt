package ru.lazyhat.compukterkraft.common.ui.dsl

import net.minecraft.network.chat.Component
import ru.lazyhat.kraftui.foundation.Value

class TranslatableValueConstant(
    key: String,
) : Value<String> {
    override val value: String = Component.translatable(key).string
}

private class TranslatableValueExpression(
    private val key: () -> String,
) : Value<String> {
    override val value: String
        get() = Component.translatable(key()).string
}

fun translatable(block: () -> String): Value<String> = TranslatableValueExpression(block)

fun translatable(key: String): Value<String> = TranslatableValueConstant(key)
