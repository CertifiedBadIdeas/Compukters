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
package ru.lazyhat.compukterkraft.common.infrastructure.workbench

import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.core.workbench.IdeRuntimeCatalogSource
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertTrue

class LanguageWorkbenchIdeFacadeTest {
    @Test
    fun completeUsesInjectedSourceIndexForUserFileAutoImports() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "main.ck" to "fun main() { ad }",
                    "lib/math.ck" to "fun add(): Int { return 1; }",
                ),
            )
        val facade =
            LanguageWorkbenchIdeFacade(
                catalogSource = StaticCatalogSource(LanguageFrontend().registry),
                sourceIndex = loader,
            )
        val source = loader.read("main.ck")!!

        val items = facade.complete("main.ck", source, line = 0, column = "fun main() { ad".length)

        assertTrue(
            items.any { it.label == "add" && it.sourceNamespace == "lib/math.ck" },
            items.joinToString { "${it.label}:${it.sourceNamespace}" },
        )
    }
}

private class StaticCatalogSource(
    private val registry: BuiltinRegistry,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): BuiltinRegistry = registry
}
