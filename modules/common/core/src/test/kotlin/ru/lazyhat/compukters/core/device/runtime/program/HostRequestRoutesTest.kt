/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class HostRequestRoutesTest {
    @Test
    fun `routes a mixed native batch in order with addon version compatibility`() {
        fun request(id: Long, namespace: String, name: String, major: Int, minor: Int, operation: Int) =
            VmHostRequest(id, CapabilityIdentity(namespace, name, major, minor), operation, emptyList())
        val firstRedstone = request(1, "compukter", "redstone", 1, 0, 6)
        val sound = request(2, "compukter", "sound", 1, 0, 0)
        val addon = request(3, "addon", "device", 1, 1, 0)
        val timer = request(4, "compukter", "timer", 1, 0, 0)
        val unsupported = request(5, "addon", "device", 1, 3, 0)
        val secondRedstone = request(6, "compukter", "redstone", 1, 0, 7)
        val schema = HostCapabilitySchema(CapabilityIdentity("addon", "device", 1, 2),
            listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        val routes = HostRequestRoutes.partition(listOf(firstRedstone, sound, addon, timer, unsupported, secondRedstone), listOf(schema))
        assertEquals(listOf(firstRedstone, secondRedstone), routes.redstone)
        assertEquals(listOf(sound), routes.sound)
        assertEquals(listOf(timer), routes.timers)
        assertEquals(listOf(addon), routes.addons)
        assertEquals(listOf(unsupported), routes.unavailable)
    }
}
