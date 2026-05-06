package ru.lazyhat.compukterkraft.core.device.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class RuntimeDeviceNoScreenSnapshotTest {
    @Test
    fun runtimeDeviceNoLongerReadsVmScreenSnapshots() {
        val root = Path.of("src/main/kotlin")
        val source =
            Files.walk(root).use { paths ->
                paths
                    .iterator()
                    .asSequence()
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .joinToString("\n") { it.readText() }
            }

        assertFalse(source.contains("readScreenSnapshot"))
        assertFalse(source.contains("forceScreenSnapshot"))
        assertFalse(source.contains("lastScreenSnapshot"))
    }
}
