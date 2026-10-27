/* Copyright 2026 Vsevolod Petrov (lazyhat). Licensed under Apache-2.0. */
import compukter.concurrent.Tasks
import compukter.input.Key
import compukter.input.Keyboard

fun main() {
    val keyboard = Keyboard.open(setOf(Key.W, Key.A, Key.S, Key.D, Key.SPACE, Key.SHIFT))
    println("Keyboard ready: configurator Keyboard mode -> click this computer.")
    println("Esc releases world controls. Ctrl+C in the terminal stops this observer.")
    try {
        while (true) {
            Tasks.sleepTicks(1)
            val state = keyboard.snapshot()
            if (state.wasPressed(Key.W)) println("W pressed")
            if (state.wasReleased(Key.W)) println("W released")
            if (state.gameTick % 20L == 0L) println("Pilot connected: " + state.connected)
        }
    } finally {
        keyboard.close()
    }
}
