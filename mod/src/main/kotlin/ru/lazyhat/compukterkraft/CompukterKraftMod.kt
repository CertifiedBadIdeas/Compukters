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

package ru.lazyhat.compukterkraft

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import ru.lazyhat.compukterkraft.platform.NetworkHandler
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingEnvironmentHolder
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingJarLoader
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingPaths

@Mod(MOD_ID)
@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
class CompukterKraftMod(
    context: FMLJavaModLoadingContext,
) {
    val scriptingJarLoader: ScriptingJarLoader = ScriptingJarLoader()

    init {
        LOGGER.info { "$MOD_ID has started!" }

        val modEventBus = context.modEventBus

        initializeScripting()

        ModRegistry.register(modEventBus)

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(::onClientSetup)
        } else {
            modEventBus.addListener(::onServerSetup)
        }

        NetworkHandler.setup()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.info { "Initializing client... with $MOD_NAME!" }
        // event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info { "Initializing server... with $MOD_NAME!" }
    }

    private fun initializeScripting() {
        val config =
            ScriptingEnvironmentConfig(
                modId = MOD_ID,
                bundledScriptsRoot = "data/$MOD_ID/kotlin",
                externalScriptsDirectory = ScriptingPaths.scriptsDirectory().absolutePath,
                definitions = listOf(ScriptDefinitionPresets.standardKts(MOD_ID)),
            )

        if (!scriptingJarLoader.hasScriptingJar()) {
            LOGGER.warn {
                "Kotlin scripting is disabled: ${ScriptingPaths.SCRIPTING_JAR} is missing in ${ScriptingPaths.rootDirectory().absolutePath}"
            }
            return
        }

        val environment = scriptingJarLoader.initialize(config)
        if (environment == null) {
            LOGGER.error { "Failed to initialize Kotlin scripting: ${scriptingJarLoader.lastError}" }
            return
        }

        LOGGER.info { "Kotlin scripting environment loaded successfully." }

        val bootstrapScript = environment.bundledScript("bios.cc.kts")
        if (bootstrapScript == null) {
            LOGGER.warn { "Bundled bootstrap script bios.cc.kts was not found." }
            return
        }

        val compilation = environment.compiler.compile("bios.cc.kts", bootstrapScript)
        if (!compilation.isSuccess) {
            LOGGER.error { "Bundled bootstrap script failed to compile: ${compilation.diagnostics.joinToString { it.message }}" }
            return
        }

        val execution = compilation.value!!.execute()
        if (!execution.isSuccess) {
            LOGGER.error { "Bundled bootstrap script failed to execute: ${execution.exceptionMessage ?: "unknown error"}" }
            return
        }

        LOGGER.info { "Bundled bootstrap script executed successfully." }
        LOGGER.debug { "Scripting available = ${ScriptingEnvironmentHolder.isAvailable}" }
    }
}
