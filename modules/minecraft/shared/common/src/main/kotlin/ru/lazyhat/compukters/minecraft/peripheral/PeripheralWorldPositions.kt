/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/** Addons project storage coordinates into the current world without loading chunks. */
object PeripheralWorldPositions {
    private var projection: (Level, Vec3) -> Vec3? = { _, point -> point }
    private var candidates: (ServerLevel, Vec3, Double) -> Sequence<BlockEntity> = { _, _, _ -> emptySequence() }

    private var installed = false

    /** A null result means the construction is unavailable; ordinary points pass through unchanged. */
    @JvmStatic
    @Synchronized
    fun install(
        project: (Level, Vec3) -> Vec3?,
        nearby: (ServerLevel, Vec3, Double) -> Sequence<BlockEntity> = { _, _, _ -> emptySequence() },
    ) {
        check(!installed) { "Peripheral world projection is already installed" }
        projection = project
        candidates = nearby
        installed = true
    }

    @JvmStatic
    fun project(
        level: Level,
        point: Vec3,
    ): Vec3? = projection(level, point)?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }

    @JvmStatic
    fun within(
        level: Level,
        first: Vec3,
        second: Vec3,
        radius: Double,
    ): Boolean {
        require(radius.isFinite() && radius >= 0)
        val a = project(level, first) ?: return false
        val b = project(level, second) ?: return false
        return a.distanceToSqr(b) <= radius * radius
    }

    internal fun nearby(
        level: ServerLevel,
        center: Vec3,
        radius: Double,
    ): Sequence<BlockEntity> = project(level, center)?.let { candidates(level, it, radius) } ?: emptySequence()

    internal fun inRange(
        level: Level,
        computer: BlockPos,
        device: PeripheralDeviceIdentity,
    ): Boolean =
        device.dimension == level.dimension().toString() &&
            within(level, Vec3.atCenterOf(computer), Vec3.atCenterOf(device.anchor), PeripheralNetworkAccess.radius().toDouble())
}
