package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ComputerStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Server-side [ComputerStdioApi] that fans out the VM's byte stream to any
 * number of attached [Consumer]s and keeps a rolling [ScrollbackRing] so a
 * late joiner receives the recent history.
 *
 * The broadcaster also maintains a [CursorTracker] fed by the same byte stream
 * so that server-side consumers (currently the line reader in
 * [VmTerminalApi][ru.lazyhat.compukterkraft.core.computer.vm.api.VmTerminalApi])
 * can query the logical cursor position without allocating a full ScreenBuffer.
 *
 * Thread model: [writeString] may be called from the VM coroutine; attach /
 * detach happens from the Minecraft server thread. All state is guarded by an
 * intrinsic lock on `this`.
 */
class ComputerStdioBroadcaster(
    scrollbackBytes: Int = 64 * 1024,
) : ComputerStdioApi {
    /**
     * Receiver of broadcast byte chunks. Implementations typically buffer
     * bytes for delivery on the next server tick.
     */
    fun interface Consumer {
        fun enqueue(bytes: ByteArray)
    }

    private val ring = ScrollbackRing(scrollbackBytes)
    private val cursorTracker = CursorTracker()
    private val cursorParser = VtParser(cursorTracker)
    private val consumers = CopyOnWriteArrayList<Consumer>()

    override fun writeString(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(this) {
            ring.append(bytes)
            cursorParser.feed(text)
        }
        for (c in consumers) c.enqueue(bytes)
    }

    fun cursor(): Pair<Int, Int> = synchronized(this) { cursorTracker.cursorX to cursorTracker.cursorY }

    /**
     * Registers [consumer] and immediately delivers a replay of all currently
     * buffered scrollback so the new attachee starts with a consistent view.
     */
    fun addConsumer(consumer: Consumer) {
        val replay = synchronized(this) { ring.snapshotBytes() }
        if (replay.isNotEmpty()) consumer.enqueue(replay)
        consumers += consumer
    }

    fun removeConsumer(consumer: Consumer) {
        consumers -= consumer
    }

    fun consumerCount(): Int = consumers.size
}
