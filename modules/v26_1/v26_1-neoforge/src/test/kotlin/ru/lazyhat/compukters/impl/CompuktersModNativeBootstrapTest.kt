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

package ru.lazyhat.compukters.impl

import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import kotlin.test.Test

class CompuktersModNativeBootstrapTest {
    @Test
    fun `mod construction loads packaged native runtime before a VM session opens`() {
        CompuktersMod.requireNativeRuntime()
        val artifact =
            checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/boot"))
                .use { it.readAllBytes() }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/shell")).use { }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/kotlinc")).use { }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/compiler/worker/compiler-k2-worker.zip")).use { }

        VmSession.open(artifact).use { }
    }
}
