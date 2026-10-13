/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package fixture.kinetics

import kotlin.enums.EnumEntries

public enum class FixtureMode { IDLE, RUNNING, STOPPED }
public enum class EmptyMode

public object Modes {
    public fun entries(): EnumEntries<FixtureMode> = FixtureMode.entries
    public fun emptyEntries(): EnumEntries<EmptyMode> = EmptyMode.entries
}
