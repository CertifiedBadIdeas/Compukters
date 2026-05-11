package ru.lazyhat.compukterkraft.core.device.vm.display

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayArchitectureTest {
    @Test
    fun coreDisplayPathDoesNotOwnKotlinFramebufferRenderer() {
        val displayRoot =
            Path.of(
                "src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display",
            )
        val forbiddenFiles =
            setOf(
                "PixelBuffer.kt",
                "DisplayState.kt",
                "TileDirtyTracker.kt",
                "Mono5x7Font.kt",
            )
        val presentForbiddenFiles =
            Files.walk(displayRoot).use { paths ->
                paths
                    .iterator()
                    .asSequence()
                    .filter { it.isRegularFile() }
                    .map { it.name }
                    .filter { it in forbiddenFiles }
                    .toList()
            }

        assertEquals(emptyList(), presentForbiddenFiles)
    }
}
