/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostResponseWire

/** Admission accounting, not measured heap size. */
object ExperimentalFleetResponseMemory {
    /**
     * Validates a response and charges its immutable value tree without allocating wire bytes.
     * Includes a conservative allowance per value/schema field and UTF-16 string storage;
     * shared schemas are counted in full, even when referenced repeatedly by the same response.
     */
    fun retainedBytes(response: HostResponse): Long = HostResponseWire.retainedBytes(response)
}
