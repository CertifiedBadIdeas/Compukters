/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import kotlin.test.Test
import kotlin.test.assertFalse

class ComputerPeripheralContractTest {
    @Test
    fun `published addon JVM constructor remains available with anchor reachability`() {
        val constructor =
            ComputerPeripheralContract::class.java.getConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                Function3::class.java,
            )
        val resolve: (Any?, Any?, Any?) -> Any? = { _, _, _ -> null }
        val contract = constructor.newInstance("test:device", "test", "device", resolve) as ComputerPeripheralContract<*>
        assertFalse(contract.ownsReachability)
    }
}
