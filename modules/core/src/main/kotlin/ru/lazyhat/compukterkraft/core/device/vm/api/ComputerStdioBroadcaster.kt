package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.DeviceStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser

/**
 * Server-side [DeviceStdioApi] that keeps legacy VM terminal writes available
 * to internal runtime diagnostics without broadcasting them to clients.
 *
 * The broadcaster also maintains a [CursorTracker] fed by the same byte stream
 * so that server-side consumers (currently the line reader in
 * [VmTerminalApi][ru.lazyhat.compukterkraft.core.device.vm.api.VmTerminalApi])
 * can query the logical cursor position without allocating a full ScreenBuffer.
 *
 * Thread model: [writeString] may be called from the VM coroutine; attach /
 * detach happens from the Minecraft server thread. All state is guarded by an
 * intrinsic lock on `this`.
 */
class ComputerStdioBroadcaster(
    private val onWrite: (String) -> Unit = {},
) : DeviceStdioApi {
    private val cursorTracker = CursorTracker()
    private val cursorParser = VtParser(cursorTracker)

    override fun writeString(text: String) {
        if (text.isEmpty()) return
        synchronized(this) {
            cursorParser.feed(text)
            onWrite(text)
        }
    }

    fun cursor(): Pair<Int, Int> = synchronized(this) { cursorTracker.cursorX to cursorTracker.cursorY }
}
