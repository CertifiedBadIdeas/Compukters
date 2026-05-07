package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NativeImageVmBindingsJniTest {
    @Test
    fun imageRunnerHaltsForEmptyMainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 128)

        try {
            val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))
            val halt = assertIs<NativeVmSignal.Halt>(signal)

            assertEquals(NativeVmValue.UnitValue, halt.value)
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    @Test
    fun imageRunnerEmitsHostCallAndResumesWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 128)

        try {
            val signal = assertIs<NativeVmSignal.HostCall>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle)))
            assertEquals("system", signal.moduleName)
            assertEquals("log", signal.functionName)
            assertEquals(listOf(NativeVmValue.StringValue("hi")), signal.arguments)

            NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("system", "log"))

            val halt = assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle)))
            assertEquals(NativeVmValue.UnitValue, halt.value)
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }
}