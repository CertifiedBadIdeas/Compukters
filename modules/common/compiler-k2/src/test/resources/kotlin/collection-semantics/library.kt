/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.redstone.RedstonePort
import fixture.kinetics.EmptyMode
import fixture.kinetics.FixtureMode
import fixture.kinetics.Modes

fun main() {
    val sides = RedstonePort.Side.entries
    val platform: Any = sides
    val expectedSides = listOf<Any?>(sides[0], sides[1], sides[2], sides[3], sides[4], sides[5], sides[6])
    check(platform == expectedSides && expectedSides == platform)
    check(platform.hashCode() == expectedSides.hashCode())
    check(platform.toString() == expectedSides.toString())
    val addon: Any = Modes.entries()
    val expectedModes = listOf<Any?>(FixtureMode.IDLE, FixtureMode.RUNNING, FixtureMode.STOPPED)
    check(addon == expectedModes && expectedModes == addon)
    check(addon.hashCode() == expectedModes.hashCode())
    check(addon.toString() == expectedModes.toString())
    val empty: Any = EmptyMode.entries
    check(empty == listOf<String>())
    check(empty.hashCode() == 1 && empty.toString() == "[]")
    println("collection library ok")
}
