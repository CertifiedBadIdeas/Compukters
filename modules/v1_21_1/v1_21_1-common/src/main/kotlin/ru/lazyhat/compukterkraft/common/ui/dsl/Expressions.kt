package ru.lazyhat.compukterkraft.common.ui.dsl

import net.minecraft.network.chat.Component
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.foundation.Value

val DslContainerScreen<*>.size
    get() = IntSize(width, height)
