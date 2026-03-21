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

import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingEnvironmentHolder
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingJarLoader
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingPaths

@Mod(CompukterKraftMod.ID)
@EventBusSubscriber(modid = CompukterKraftMod.ID, bus = EventBusSubscriber.Bus.MOD)
open class CompukterKraftMod {
    companion object {
        const val ID = "compukterkraft"
        val SCRIPTING_LOADER = ScriptingJarLoader()
    }

    val LOGGER: Logger = LogManager.getLogger(ID)
    val installedVersion =
        ModList
            .get()
            .getModContainerById(ID)
            .map {
                it.modInfo.version.toString()
            }.orElse("unknown")

    init {
        LOGGER.log(Level.INFO, "$ID has started!")

        initializeScripting()

        // ModRegistry.register()

//        safeRunForDist(
//            {
//                // MOD_BUS.addListener(::onClientSetup)
//            },
//            {
//                // MOD_BUS.addListener(::onServerSetup)
//            },
//        )

        // NetworkHandler.setup()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client... with Compukter Craft!")
        // event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing server... with Compukter Craft!")
    }

    private fun initializeScripting() {
        val config =
            ScriptingEnvironmentConfig(
                modId = ID,
                bundledScriptsRoot = "data/$ID/kotlin",
                externalScriptsDirectory = ScriptingPaths.scriptsDirectory().absolutePath,
                definitions = listOf(ScriptDefinitionPresets.standardKts(ID)),
            )

        if (!SCRIPTING_LOADER.hasScriptingJar()) {
            LOGGER.warn(
                "Kotlin scripting is disabled: {} is missing in {}",
                ScriptingPaths.SCRIPTING_JAR,
                ScriptingPaths.rootDirectory().absolutePath,
            )
            return
        }

        val environment = SCRIPTING_LOADER.initialize(config)
        if (environment == null) {
            LOGGER.error("Failed to initialize Kotlin scripting: {}", SCRIPTING_LOADER.lastError)
            return
        }

        LOGGER.info("Kotlin scripting environment loaded successfully.")

        val bootstrapScript = environment.bundledScript("bios.cc.kts")
        if (bootstrapScript == null) {
            LOGGER.warn("Bundled bootstrap script bios.cc.kts was not found.")
            return
        }

        val compilation = environment.compiler.compile("bios.cc.kts", bootstrapScript)
        if (!compilation.isSuccess) {
            LOGGER.error("Bundled bootstrap script failed to compile: {}", compilation.diagnostics.joinToString { it.message })
            return
        }

        val execution = compilation.value!!.execute()
        if (!execution.isSuccess) {
            LOGGER.error("Bundled bootstrap script failed to execute: {}", execution.exceptionMessage ?: "unknown error")
            return
        }

        LOGGER.info("Bundled bootstrap script executed successfully.")
        LOGGER.debug("Scripting available = {}", ScriptingEnvironmentHolder.isAvailable)
    }
}
