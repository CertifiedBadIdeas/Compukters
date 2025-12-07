package ru.lazyhat.compuktercraft.data

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compuktercraft.Config
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.gui.TerminalState

class ComputerContainerData private constructor(
    val family: ComputerFamily,
    val terminalState: TerminalState,
    val displayStack: ItemStack,
    val uploadMaxSize: Int,
) : IContainerData {
    constructor(buffer: FriendlyByteBuf) : this(
        buffer.readEnum(ComputerFamily::class.java),
        TerminalState(buffer),
        buffer.readItem(),
        buffer.readInt().also {
            // CompukterCraftMod.LOGGER.info("ComputerContainerData init from buffer")
        },
    )

    override fun toBytes(buffer: FriendlyByteBuf) {
        buffer.writeEnum(family)
        terminalState.write(buffer)
        buffer.writeItem(displayStack)
        buffer.writeInt(uploadMaxSize)
        // CompukterCraftMod.LOGGER.info("ComputerContainerData write to buffer")
    }

    constructor(computer: ServerComputer, displayStack: ItemStack) : this(
        computer.family.also {
            // CompukterCraftMod.LOGGER.info("ComputerContainerData standard init")
        },
        TerminalState.create(computer.terminal),
        displayStack,
        Config.uploadMaxSize,
    )
}
