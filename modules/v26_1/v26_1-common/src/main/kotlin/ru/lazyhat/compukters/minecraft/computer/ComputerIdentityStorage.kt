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

import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.UUID

internal class ComputerIdentityStorage(
    private val identitySource: () -> ComputerId = ::randomComputerId,
) {
    private var identity = identitySource()

    fun id(): ComputerId = identity

    fun save(output: ValueOutput) {
        output.putLong(HIGH_KEY, identity.highBits)
        output.putLong(LOW_KEY, identity.lowBits)
    }

    fun load(input: ValueInput?) {
        val high = input?.getLong(HIGH_KEY)?.orElse(null)
        val low = input?.getLong(LOW_KEY)?.orElse(null)
        identity =
            if (high == null || low == null || (high == 0L && low == 0L)) {
                identitySource()
            } else {
                ComputerId.fromLongs(high, low)
            }
    }

    private companion object {
        const val HIGH_KEY = "computer_id_high"
        const val LOW_KEY = "computer_id_low"
    }
}

private fun randomComputerId(): ComputerId {
    while (true) {
        val candidate = UUID.randomUUID()
        if (candidate.mostSignificantBits != 0L || candidate.leastSignificantBits != 0L) {
            return ComputerId.fromLongs(candidate.mostSignificantBits, candidate.leastSignificantBits)
        }
    }
}
