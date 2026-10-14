/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Vector3d

value class HorizontalCommand(
    val targetUp: Vector3d,
    val distance: Double,
    val speed: Double,
    val centered: Boolean,
)

// Slow outer PD loop: the off-centre nozzle also translates while changing attitude.
// Use pose motion so the velocity and stored pad reference describe the same point on the rocket.
fun padGuidance(
    padOffset: Vector3d,
    positionVelocity: Vector3d,
): HorizontalCommand {
    val offset = Vector3d(padOffset.x, 0.0, padOffset.z)
    val velocity = Vector3d(positionVelocity.x, 0.0, positionVelocity.z)
    require(offset.length <= 2.0 && velocity.length <= 2.0) { "Outside horizontal flight envelope" }
    val requested = offset * -0.02 - velocity * 0.2
    val acceleration = if (requested.length > 0.5) requested.normalized() * 0.5 else requested
    return HorizontalCommand(
        Vector3d(acceleration.x, 9.81, acceleration.z).normalized(),
        offset.length,
        velocity.length,
        offset.length <= 0.2 && velocity.length <= 0.1,
    )
}
