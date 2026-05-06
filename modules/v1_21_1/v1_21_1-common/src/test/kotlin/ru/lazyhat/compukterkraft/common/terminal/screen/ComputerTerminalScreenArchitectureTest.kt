package ru.lazyhat.compukterkraft.common.terminal.screen

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerTerminalScreenArchitectureTest {
    private val source =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt")
            .readText()

    @Test
    fun computerScreenUsesDisplayBufferNotTerminalBuffer() {
        assertFalse(source.contains("ClientTerminalBuffer"))
        assertFalse(source.contains("AttachTerminalServerMessage"))
        assertFalse(source.contains("ResizeTerminalServerMessage"))
        assertFalse(source.contains("terminalSurface("))
        assertTrue(source.contains("ClientDisplayBuffer"))
        assertTrue(source.contains("DisplayAttachServerMessage"))
        assertTrue(source.contains("DisplayResizeServerMessage"))
    }

    @Test
    fun computerScreenRendersDisplayAsTextureNotPerPixelGuiRects() {
        assertTrue(source.contains("NativeImage"))
        assertTrue(source.contains("DynamicTexture"))
        assertTrue(source.contains("drawDisplayTexture"))
        assertFalse(source.contains("frontArgb()"))
        assertFalse(source.contains("while (x < buffer.width)"))
        assertFalse(source.contains("fillRect(px, py, pw, ph, color)"))
    }
}