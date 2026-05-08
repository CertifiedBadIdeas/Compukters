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

package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CkVmHostImportRegistryTest {
    @Test
    fun stableIdsAreAssignedForRepresentativeHostImports() {
        assertEquals(
            CkVmHostImport(1011, "display", "present", listOf("Int"), "Unit"),
            CkVmHostImportRegistry.require("display", "present", argumentCount = 1),
        )
        assertEquals(
            CkVmHostImport(3004, "system", "log", listOf("String"), "Unit"),
            CkVmHostImportRegistry.require("system", "log", argumentCount = 1),
        )
    }

    @Test
    fun allHostImportIdsAreUnique() {
        val ids = CkVmHostImportRegistry.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun registryCoversEveryRuntimeBuiltinModuleFunction() {
        val missing =
            LanguageBuiltins.defaultRuntimeRegistry.modules.flatMap { module ->
                module.functions.mapNotNull { function ->
                    val descriptor = CkVmHostImportRegistry.find(module.name, function.name, function.parameterTypes.size)
                    if (descriptor == null) {
                        "${module.name}::${function.name}/${function.parameterTypes.size}"
                    } else {
                        assertEquals(function.parameterTypes, descriptor.parameterTypes)
                        assertEquals(function.returnType, descriptor.returnType)
                        null
                    }
                }
            }

        assertTrue(missing.isEmpty(), "Missing host import descriptors: $missing")
    }

    @Test
    fun overloadedFunctionsResolveByArgumentCount() {
        val listRoot = assertNotNull(CkVmHostImportRegistry.find("filesystem", "list", argumentCount = 0))
        val listDirectory = assertNotNull(CkVmHostImportRegistry.find("filesystem", "list", argumentCount = 1))

        assertEquals(2006, listRoot.id)
        assertEquals(2007, listDirectory.id)
        assertEquals(emptyList(), listRoot.parameterTypes)
        assertEquals(listOf("String"), listDirectory.parameterTypes)
    }
}
