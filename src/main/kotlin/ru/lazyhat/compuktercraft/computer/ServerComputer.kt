package ru.lazyhat.compuktercraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.Config
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.context.ServerContext
import ru.lazyhat.compuktercraft.gui.NetworkedTerminal
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    val blockPos: BlockPos,
    properties: ComputerProperties,
) : ComputerEvents.Receiver {
    val family = properties.family
    val terminal =
        NetworkedTerminal(
            Config.DEFAULT_COMPUTER_TERM_WIDTH,
            Config.DEFAULT_COMPUTER_TERM_HEIGHT,
            family != ComputerFamily.NORMAL,
        )

    init {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID init")
    }

    var isOn = false
        private set

    fun checkUsable(player: Player) = true

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID event $event")
    }

    fun shutdown() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID shutdown")
        isOn = false
    }

    fun turnOn() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID turnOn")
        isOn = true
        val biosStream =
            ServerContext
                .server
                .resourceManager
                .getResource(
                    ResourceLocation
                        .fromNamespaceAndPath(CompukterCraftMod.ID, "kotlin/bios.cc.kts"),
                ).orElse(null)!!
                .open()
        BasicJvmScriptingHost().eval(
            biosStream.readAllBytes().decodeToString().toScriptSource(),
            ComputerScriptCompilationConfiguration(),
            null,
        )
    }

    fun reboot() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID reboot")
    }

    fun close() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID close")
        isOn = false
    }
}
