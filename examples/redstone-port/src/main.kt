/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.redstone.Redstone
import compukter.redstone.RedstonePort

fun main() {
    val relay = RedstonePort.named("relay")
    try {
        while (true) {
            relay.front.awaitAtLeast(7)
            relay.right.set(15, Redstone.Power.DIRECT)
            relay.front.await(0)
            relay.right.set(0)
        }
    } finally {
        relay.close()
    }
}
