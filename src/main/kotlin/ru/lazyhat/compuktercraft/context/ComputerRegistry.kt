package ru.lazyhat.compuktercraft.context

import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import ru.lazyhat.compuktercraft.computer.ServerComputer
import java.util.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ComputerRegistry {
    companion object {
        @OptIn(ExperimentalTime::class)
        val RANDOM = Random(Clock.System.now().toEpochMilliseconds())
    }

    val sessionId = RANDOM.nextInt()
    private val computersByInstanceId: Int2ObjectMap<ServerComputer> = Int2ObjectOpenHashMap()

    fun getServerComputer(instanceId: Int): ServerComputer? = computersByInstanceId[instanceId]

    fun addServerComputer(serverComputer: ServerComputer) {
        check(!computersByInstanceId.containsKey(serverComputer.instanceID)) {
            "Computer with ${serverComputer.instanceID} already exists!"
        }

        computersByInstanceId.put(serverComputer.instanceID, serverComputer)
    }
}
