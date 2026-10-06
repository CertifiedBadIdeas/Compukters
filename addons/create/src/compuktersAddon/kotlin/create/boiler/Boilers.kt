/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package create.boiler

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** A side of the computer, relative to its front face. */
public value class BoilerSide internal constructor(internal val index: Int) {
    init {
        require(index in 0..5)
    }

    public fun boiler(): Boiler = Boiler(BoilerBindings.acquire(index))
}

/** A handle to one active Create boiler and its current Fluid Tank controller. */
public class Boiler internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<Boiler>("create:boiler") {
        override fun wrap(handle: Int): Boiler = Boiler(handle)
    }

    override fun equals(other: Any?): Boolean = other is Boiler && handle == other.handle

    override fun hashCode(): Int = handle

    override fun toString(): String = "Boiler(handle=$handle)"

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

public object Boilers {
    public fun boiler(name: String): Boiler = Boiler(BoilerBindings.acquireByName(name))

    public val front: BoilerSide get() = BoilerSide(0)
    public val back: BoilerSide get() = BoilerSide(1)
    public val left: BoilerSide get() = BoilerSide(2)
    public val right: BoilerSide get() = BoilerSide(3)
    public val top: BoilerSide get() = BoilerSide(4)
    public val bottom: BoilerSide get() = BoilerSide(5)
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
