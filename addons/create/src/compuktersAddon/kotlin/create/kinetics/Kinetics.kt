/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package create.kinetics

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

public value class Speedometer internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<Speedometer>("create:speedometer") {
        override fun wrap(handle: Int): Speedometer = Speedometer(handle)
    }

    public fun speed(): Float = KineticsBindings.speed(handle)

    public fun awaitSpeedChange(): Float = KineticsBindings.awaitSpeedChange(handle)
}

public value class Stressometer internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<Stressometer>("create:stressometer") {
        override fun wrap(handle: Int): Stressometer = Stressometer(handle)
    }

    public fun stress(): Float = KineticsBindings.stress(handle)

    public fun capacity(): Float = KineticsBindings.capacity(handle)

    public fun awaitChange() {
        KineticsBindings.awaitStressChange(handle)
    }
}

public value class RotationController internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<RotationController>("create:rotation_controller") {
        override fun wrap(handle: Int): RotationController = RotationController(handle)
    }

    public fun targetSpeed(): Int = KineticsBindings.targetSpeed(handle)

    public fun setTargetSpeed(speed: Int): Int = KineticsBindings.setTargetSpeed(handle, speed)
}

private object KineticsBindings {
    external fun acquireSpeedometer(side: Int): Int

    external fun speed(handle: Int): Float

    external fun awaitSpeedChange(handle: Int): Float

    external fun acquireStressometer(side: Int): Int

    external fun stress(handle: Int): Float

    external fun capacity(handle: Int): Float

    external fun awaitStressChange(handle: Int)

    external fun acquireRotationController(side: Int): Int

    external fun targetSpeed(handle: Int): Int

    external fun setTargetSpeed(handle: Int, speed: Int): Int

    external fun acquireSpeedometerByName(name: String): Int

    external fun acquireStressometerByName(name: String): Int

    external fun acquireRotationControllerByName(name: String): Int
}
