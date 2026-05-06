package ru.lazyhat.compukterkraft.lang.runtime

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class RuntimeHostBridgeArchitectureTest {
    @Test
    fun bridgeDoesNotDispatchTerminalStdout() {
        val source = Paths.get("src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt").readText()

        assertFalse(source.contains("invokeTerminal"))
        assertFalse(source.contains("invokeStdout"))
        assertFalse(source.contains("\"terminal\" ->"))
        assertFalse(source.contains("\"stdout\" ->"))
    }
}
