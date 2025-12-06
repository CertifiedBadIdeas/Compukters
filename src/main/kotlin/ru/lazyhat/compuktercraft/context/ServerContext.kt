package ru.lazyhat.compuktercraft.context

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.computer.ComputerProperties
import ru.lazyhat.compuktercraft.computer.ServerComputer
import java.util.UUID

// private val LOGGER: Logger = LogManager.getLogger(ServerContext::class.java)

object ServerContext {
    private val computers: HashMap<UUID, ServerComputer> = hashMapOf()

    fun getComputer(instanceUUID: UUID?): ServerComputer? = instanceUUID?.let { computers[instanceUUID] }

    fun createComputer(
        level: ServerLevel,
        pos: BlockPos,
        family: ComputerFamily,
    ): Pair<UUID, ServerComputer> =
        ServerComputer(
            level,
            pos,
            ComputerProperties(computers.size, family),
        ).let {
            val uuid = UUID.randomUUID()
            computers[uuid] = it
            uuid to it
        }
}
