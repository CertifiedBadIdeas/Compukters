package ru.lazyhat.compukterkraft.common.ui.dsl

import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize

val DslContainerScreen<*>.size
    get() = IntSize(width, height)
