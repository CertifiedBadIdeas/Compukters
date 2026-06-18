/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.kraftui.foundation

interface Value<out T> {
    val value: T
}

@JvmInline
value class ValueConstant<T>(
    override val value: T,
) : Value<T>

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

fun <T> value(value: T): Value<T> = ValueConstant(value)

fun <T> value(block: () -> T): Value<T> = ValueExpression(block)

/**
 * A [Value] whose computation receives the current monotonic UI tick. The tick
 * counter is incremented by [ru.lazyhat.kraftui.program.ScreenRuntimeExecutor.render]
 * before walking render ops. Useful for blink/animation primitives, e.g.:
 *
 *     val cursorVisible = tickValue { it / 6 % 2 == 0 }
 */
fun <T> tickValue(block: (Int) -> T): Value<T> = TickValueExpression(block)

/**
 * Holds the current monotonic UI tick. Updated by
 * [ru.lazyhat.kraftui.program.ScreenRuntimeExecutor.render] just
 * before rendering each frame. Read-only for DSL users; expose only through
 * [tickValue].
 */
object TickContext {
    var current: Int = 0
        internal set
}
