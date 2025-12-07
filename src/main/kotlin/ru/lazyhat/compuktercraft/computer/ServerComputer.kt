package ru.lazyhat.compuktercraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.Config
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.gui.NetworkedTerminal

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
    }

    fun reboot() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID reboot")
    }

    fun close() {
        CompukterCraftMod.LOGGER.info("ComputerID: $instanceID close")
        isOn = false
    }
}
