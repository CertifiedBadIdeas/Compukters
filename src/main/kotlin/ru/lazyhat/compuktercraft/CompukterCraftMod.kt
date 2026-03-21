package ru.lazyhat.compuktercraft

import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.lazyhat.compuktercraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compuktercraft.scripting.runtime.ScriptingEnvironmentHolder
import ru.lazyhat.compuktercraft.scripting.runtime.ScriptingJarLoader
import ru.lazyhat.compuktercraft.scripting.runtime.ScriptingPaths

@Mod(CompukterCraftMod.ID)
@EventBusSubscriber(modid = CompukterCraftMod.ID, bus = EventBusSubscriber.Bus.MOD)
open class CompukterCraftMod {
    companion object {
        const val ID = "compuktercraft"
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
        val config = ScriptingEnvironmentConfig(
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
