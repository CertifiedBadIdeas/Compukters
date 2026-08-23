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
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test

class CompuktersModNativeBootstrapTest {
    @Test
    fun `mod construction loads packaged native runtime before a VM session opens`() {
        CompuktersMod()
        val encoded = Path.of(requiredProperty("compukter.vm.terminalFixture")).readText().trim()
        require(encoded.length % 2 == 0) { "fixture contains incomplete hexadecimal byte" }
        val artifact =
            ByteArray(encoded.length / 2) { index ->
                encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }

        VmSession.open(artifact).use { }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }
}
