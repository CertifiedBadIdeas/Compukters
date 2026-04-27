/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.common.peripheral

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry of [BlockPeripheralProvider]s contributed by addon modules.
 *
 * Providers are registered during loader bootstrap (after the addon's guarded init confirms its
 * target mod is loaded) and queried at runtime when a computer needs to discover what kind of
 * peripheral sits at a given block face. The first provider that returns a non-null descriptor
 * wins, so registration order encodes priority — addons should register specific providers before
 * generic fallbacks.
 *
 * The registry intentionally uses a [CopyOnWriteArrayList] so reads from the server tick thread
 * never see torn state while a late mod loader thread is finishing registration.
 */
object BlockPeripheralRegistry {
    private val providers = CopyOnWriteArrayList<BlockPeripheralProvider>()

    fun register(provider: BlockPeripheralProvider) {
        providers.add(provider)
    }

    fun lookup(context: BlockPeripheralContext): BlockPeripheralDescriptor? = providers.firstNotNullOfOrNull { it.provide(context) }

    val registeredCount: Int
        get() = providers.size

    /**
     * Test-only escape hatch. Production code must never call this — provider registration is
     * meant to be additive and live for the lifetime of the JVM.
     */
    internal fun resetForTesting() {
        providers.clear()
    }
}
