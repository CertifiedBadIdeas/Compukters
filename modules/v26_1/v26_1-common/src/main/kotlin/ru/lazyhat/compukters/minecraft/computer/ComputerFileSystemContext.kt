/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore

class ComputerFileSystemContext(
    val store: WorldFileSystemStore,
    val computerId: ComputerId,
    romImage: ByteArray,
    private val lifecycle: ComputerFileSystemLifecycle,
    internal val compilerRouter: CompilerCompletionRouter? = null,
) {
    private val romImage = romImage.copyOf()

    internal fun romImage(): ByteArray = romImage.copyOf()

    internal fun attach(
        generation: () -> Long?,
        drain: () -> Long?,
    ): ComputerFileSystemLease = lifecycle.attach(computerId, generation, drain)
}

fun interface ComputerFileSystemContextSource {
    fun create(
        level: ServerLevel,
        computerId: ComputerId,
        romImage: ByteArray,
    ): ComputerFileSystemContext

    fun tombstone(
        level: ServerLevel,
        computerId: ComputerId,
    ) = Unit
}

fun interface ComputerFileSystemLifecycle {
    fun attach(
        computerId: ComputerId,
        generation: () -> Long?,
        drain: () -> Long?,
    ): ComputerFileSystemLease
}

fun interface ComputerFileSystemLease {
    fun release(generation: Long?)
}
