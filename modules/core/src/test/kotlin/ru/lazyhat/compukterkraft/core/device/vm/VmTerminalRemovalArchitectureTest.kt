package ru.lazyhat.compukterkraft.core.device.vm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class VmTerminalRemovalArchitectureTest {
    @Test
    fun coreMainDoesNotReferenceVmTerminalStdoutImplementations() {
        val root = Path.of("src/main/kotlin")
        val source =
            Files.walk(root).use { paths ->
                paths
                    .iterator()
                    .asSequence()
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .joinToString("\n") { it.readText() }
            }

        listOf(
            "DeviceTerminalApi",
            "DeviceStdioApi",
            "VmTerminalApi",
            "ComputerStdioBroadcaster",
            "ScreenBufferVtSink",
            "CursorTracker",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "core main must not reference $forbidden")
        }
    }
}
