package ru.lazyhat.compukterkraft.core.device.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StdioDescriptorTest {
    @Test
    fun decodesTaggedDescriptor() {
        val descriptor = StdioDescriptor.decode("stdio-v1 3 4 5 hello world")

        assertEquals(StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = "hello world"), descriptor)
    }

    @Test
    fun decodesTaggedDescriptorWithBlankArgument() {
        val descriptor = StdioDescriptor.decode("stdio-v1 3 4 5 ")

        assertEquals(StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = ""), descriptor)
    }

    @Test
    fun rejectsUntaggedLegacyDescriptor() {
        assertNull(StdioDescriptor.decode("3 4 5 hello"))
    }

    @Test
    fun rejectsMalformedDescriptor() {
        assertNull(StdioDescriptor.decode("stdio-v1 input 4 5 hello"))
        assertNull(StdioDescriptor.decode("stdio-v1 3 4"))
        assertNull(StdioDescriptor.decode("stdio-v2 3 4 5 hello"))
    }

    @Test
    fun encodesTaggedDescriptor() {
        val text = StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = "hello world").encode()

        assertEquals("stdio-v1 3 4 5 hello world", text)
    }
}
