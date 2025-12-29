package ru.lazyhat.compuktercraft.context

import net.minecraft.server.MinecraftServer
import ru.lazyhat.compuktercraft.utils.SingletonHolder

// private val LOGGER: Logger = LogManager.getLogger(ServerContext::class.java)

class ServerContext(
    val server: MinecraftServer,
) {
    val registry = ComputerRegistry()

    companion object : SingletonHolder<ServerContext>() {
        val registry
            get() = instance.registry

        val server
            get() = instance.server

        fun create(server: MinecraftServer) {
            instance = ServerContext(server)
        }

        fun close() {
            resetInstance()
        }
    }
}
