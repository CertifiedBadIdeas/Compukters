/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package create.kinetics

public value class KineticSide internal constructor(internal val index: Int) {
    init {
        require(index in 0..5)
    }

    public fun speedometer(): Speedometer = Speedometer(KineticsBindings.acquireSpeedometer(index))

    public fun stressometer(): Stressometer = Stressometer(KineticsBindings.acquireStressometer(index))

    public fun rotationController(): RotationController = RotationController(KineticsBindings.acquireRotationController(index))
}

public value class Speedometer internal constructor(private val handle: Int) {
    public fun speed(): Float = KineticsBindings.speed(handle)

    public fun awaitSpeedChange(): Float = KineticsBindings.awaitSpeedChange(handle)
}

public value class Stressometer internal constructor(private val handle: Int) {
    public fun stress(): Float = KineticsBindings.stress(handle)

    public fun capacity(): Float = KineticsBindings.capacity(handle)

    public fun awaitChange() {
        KineticsBindings.awaitStressChange(handle)
    }
}

public value class RotationController internal constructor(private val handle: Int) {
    public fun targetSpeed(): Int = KineticsBindings.targetSpeed(handle)

    public fun setTargetSpeed(speed: Int): Int = KineticsBindings.setTargetSpeed(handle, speed)
}

public object Kinetics {
    public val front: KineticSide
        get() = KineticSide(0)

    public val back: KineticSide
        get() = KineticSide(1)

    public val left: KineticSide
        get() = KineticSide(2)

    public val right: KineticSide
        get() = KineticSide(3)

    public val top: KineticSide
        get() = KineticSide(4)

    public val bottom: KineticSide
        get() = KineticSide(5)
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
}
