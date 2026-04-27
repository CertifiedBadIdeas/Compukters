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

package ru.lazyhat.compukterkraft.impl.create

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateCompatBootstrapTest {
    @Test
    fun initializeIfPresentOnlyRunsRegistrarWhenCreateIsInstalled() {
        var registrations = 0

        val absentResult =
            CreateCompatBootstrap.initializeIfPresent(
                isCreateLoaded = { false },
                register = { registrations++ },
            )
        val presentResult =
            CreateCompatBootstrap.initializeIfPresent(
                isCreateLoaded = { true },
                register = { registrations++ },
            )

        assertFalse(absentResult)
        assertTrue(presentResult)
        assertTrue(registrations == 1)
    }
}
