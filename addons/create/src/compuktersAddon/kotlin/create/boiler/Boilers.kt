/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package create.boiler

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** A side of the computer, relative to its front face. */

/** A handle to one active Create boiler and its current Fluid Tank controller. */
public value class Boiler internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<Boiler>("create:boiler") {
        override fun wrap(handle: Int): Boiler = Boiler(handle)
    }

    /** Create's sampled water input rate, in millibuckets per tick. */
    public fun waterSupply(): Float = BoilerBindings.waterSupply(handle)

    /** Water contribution to the boiler level, from zero through 18. */
    public fun waterLevel(): Int = BoilerBindings.waterLevel(handle)

    /** Create's heat gauge before water and tank-size limits. Passive heat reports one. */
    public fun heatLevel(): Int = BoilerBindings.heatLevel(handle)

    /** Effective active boiler level, from zero through 18. Passive heat reports zero. */
    public fun level(): Int = BoilerBindings.level(handle)

    /** Whether Create currently treats the boiler as passively heated. */
    public fun isPassive(): Boolean = BoilerBindings.isPassive(handle)
}

private object BoilerBindings {
    external fun acquire(side: Int): Int
    external fun acquireByName(name: String): Int
    external fun waterSupply(handle: Int): Float
    external fun waterLevel(handle: Int): Int
    external fun heatLevel(handle: Int): Int
    external fun level(handle: Int): Int
    external fun isPassive(handle: Int): Boolean
}
