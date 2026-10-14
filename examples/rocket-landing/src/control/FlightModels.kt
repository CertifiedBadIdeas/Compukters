/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

enum class FlightPhase { ASCENT, HOLD, DESCENT, COMPLETE }

value class FlightState(
    val phase: FlightPhase,
    val settledSeconds: Double,
    val integral: Double,
)

value class FlightCommand(
    val state: FlightState,
    val targetSpeed: Double,
    val thrustKn: Double,
    val vectorX: Double,
    val vectorY: Double,
)
