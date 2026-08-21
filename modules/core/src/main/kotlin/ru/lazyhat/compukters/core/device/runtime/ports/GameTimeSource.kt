package ru.lazyhat.compukters.core.device.runtime.ports

/** Supplies the current server game time (in ticks) to a runtime device. */
fun interface GameTimeSource {
    fun gameTime(): Long
}
