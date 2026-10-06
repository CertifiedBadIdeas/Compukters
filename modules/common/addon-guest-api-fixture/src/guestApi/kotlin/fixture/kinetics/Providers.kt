/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package fixture.kinetics

public interface FixtureProvider<T> {
    public fun next(): T

    public fun firstOrNull(predicate: (T) -> Boolean): T? {
        val device = next()
        return if (predicate(device)) device else null
    }
}

public class ProbeDevice(public val value: Int) {
    public companion object : FixtureProvider<ProbeDevice> {
        override fun next(): ProbeDevice = ProbeDevice(7)
    }
}
