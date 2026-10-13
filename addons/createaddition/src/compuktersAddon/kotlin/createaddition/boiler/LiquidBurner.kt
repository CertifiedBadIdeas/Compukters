/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package createaddition.boiler

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** One atomic server-tick observation. Heat: 0=none, 1=smouldering, 2=fading, 3=heated, 4=seething. */
public data class BurnerStatus(val fuelAmount: Int, val fuelCapacity: Int, val heat: Int, val burnTicks: Int)

/** A Crafts & Additions liquid Blaze Burner created by applying a straw. Read-only. */
public value class LiquidBurner internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<LiquidBurner>("createaddition:liquid_burner") {
        override fun wrap(handle: Int): LiquidBurner = LiquidBurner(handle)
    }

    /** Recoverable liquid is separate from fuel already converted into burning time. */
    public fun status(): BurnerStatus = LiquidBurnerBindings.status(handle)
}

private object LiquidBurnerBindings {
    external fun status(handle: Int): BurnerStatus
}
