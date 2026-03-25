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

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.scripting.api.ScriptCompiler
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionDescriptor
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptIdeService
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig

class ScriptingEnvironmentImpl(
    override val config: ScriptingEnvironmentConfig,
) : ScriptingEnvironment {
    override val definitions: List<ScriptDefinitionDescriptor> =
        config.definitions.ifEmpty { listOf(ScriptDefinitionPresets.computerKts(config.modId)) }

    internal val frontend = LanguageFrontend(LanguageBuiltins.registry)

    override val compiler: ScriptCompiler = ScriptCompilerImpl(this)
    override val ide: ScriptIdeService = ScriptIdeServiceImpl(this)
    override val isAvailable: Boolean = true

    override fun bundledScript(relativePath: String): String? {
        val normalizedPath = "${config.bundledScriptsRoot.trim('/')}/$relativePath"
        return javaClass.classLoader.getResourceAsStream(normalizedPath)?.bufferedReader()?.use { it.readText() }
    }

    override fun close() = Unit
}
