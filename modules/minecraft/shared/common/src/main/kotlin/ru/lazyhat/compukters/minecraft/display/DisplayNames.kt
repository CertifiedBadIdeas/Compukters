/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupResult
import ru.lazyhat.compukters.minecraft.peripheral.ComputerPeripheralLookupStatus
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralDeviceIdentity

internal object DisplayNames {
    fun rename(
        level: ServerLevel,
        id: java.util.UUID,
        name: String?,
    ) {
        DisplayStorage.get(level).directory.rename(id, name)
        ru.lazyhat.compukters.minecraft.peripheral.DisplayNetworkAccess.refresh(
            level,
            requireNotNull(DisplayStorage.get(level).directory.byId(id)),
        )
    }

    fun surface(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
    ): ru.lazyhat.compukters.core.display.DisplaySurface? {
        if (identity.providerId != "compukters-display") return null
        if (level.hasChunkAt(identity.anchor)) {
            val entity = level.getBlockEntity(identity.anchor) as? DisplayBlockEntity ?: return null
            return DisplayWorldAccess.surface(level, entity)
        }
        return DisplayStorage.get(level).directory.snapshot().firstOrNull { screen ->
            screen.panels.any { panel -> DisplayWorldAccess.position(screen, panel.column, panel.row) == identity.anchor }
        }
    }

    fun lookup(
        level: ServerLevel,
        requested: String,
        contacts: Set<PeripheralDeviceIdentity>,
    ): ComputerPeripheralLookupResult {
        val normalized =
            ru.lazyhat.compukters.minecraft.peripheral
                .normalizePeripheralName(requested)
        val matches =
            contacts
                .mapNotNull { identity -> surface(level, identity)?.let { it to identity } }
                .filter { it.first.name == normalized }
                .distinctBy { it.first.id }
        return when (matches.size) {
            0 -> {
                ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.MISSING)
            }

            1 -> {
                matches.single().second.let {
                    ComputerPeripheralLookupResult(
                        ComputerPeripheralLookupStatus.FOUND,
                        ComputerPeripheralIdentity(it.providerId, it.anchor, it.deviceKey),
                    )
                }
            }

            else -> {
                ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.AMBIGUOUS)
            }
        }
    }
}
