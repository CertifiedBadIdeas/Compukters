/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package fixture.kinetics

public interface Position {
    public val x: Double
    public fun moved(delta: Double): Vec2
}

public value class Vec2(override val x: Double, public val y: Double) : Position {
    override fun moved(delta: Double): Vec2 = Vec2(x + delta, y + delta)
}

public value class Envelope(public val point: Vec2, public val label: String, public val available: Boolean)

public value class ReferenceValue(public val text: String)

public value class Duo<T>(public val first: T, public val second: T) {
    public fun swapped(): Duo<T> = Duo(second, first)
}

public object Geometry {
    public fun duo(): Duo<Vec2> = Duo(Vec2(1.0, 2.0), Vec2(3.0, 4.0))
    public fun boxedDuo(): Any = duo()
    public fun point(): Vec2 = Vec2(1.0, 2.0)
    public fun relay(point: Vec2): Vec2 = point.moved(1.0)
    public fun position(point: Vec2): Position = point
    public fun envelope(point: Vec2, label: String): Envelope = Envelope(point, label, true)
    public fun boxedEnvelope(): Any = Envelope(Vec2(3.0, 4.0), "library", true)
    public fun isEnvelope(value: Any): Boolean = value is Envelope
    public fun unpack(value: Any): Envelope = value as Envelope
    public fun optional(value: Any): Envelope? = value as? Envelope
    public fun boxedPoint(): Any = Vec2(1.0, 2.0)
    public fun reference(): ReferenceValue = ReferenceValue("reference")
}
