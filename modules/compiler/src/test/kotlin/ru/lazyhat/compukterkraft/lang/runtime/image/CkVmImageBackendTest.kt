package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CkVmImageBackendTest {
    @Test
    fun compileImageCreatesEntryFunctionForEmptyMain() {
        val artifact = LanguageFrontend().compileImage("main.ck", "pub fun main() { }")
        val image = assertNotNull(artifact.image)

        assertEquals("ckl-1", image.languageVersion)
        assertEquals(1, image.targetAbiVersion)
        assertEquals(0, image.entryFunctionIndex)
        assertEquals("main.ck#main", image.functions.single().name)
        assertContentEquals(listOf(CkVmImageOpcodes.PUSH_UNIT, CkVmImageOpcodes.RETURN), image.functions.single().code)
    }

    @Test
    fun compileImageLowersSystemLogToConstantAndHostImport() {
        val artifact = LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }")
        val image = assertNotNull(artifact.image)

        assertEquals(listOf(CkVmConstant.StringConstant("hi")), image.constants)
        assertEquals(listOf(CkVmHostImport(0, "system", "log", listOf("Any"), "Unit")), image.hostImports)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 0, 0, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageReturnsNullImageWhenFrontendHasErrors() {
        val artifact = LanguageFrontend().compileImage("main.ck", "fun main() { }")

        assertNull(artifact.image)
        assertTrue(artifact.bytecode.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR })
    }

    @Test
    fun unsupportedInstructionReportsClearError() {
        val artifact = LanguageFrontend().compile("main.ck", "pub fun main() { if (true) { system::log(\"x\"); } }")
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support"))
    }

    @Test
    fun writesBackendFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.image.backend.fixture.path")?.takeIf(String::isNotBlank) ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)

        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
        java.nio.file.Files.write(java.nio.file.Path.of(path), CkVmImageAbi.encode(image))
    }
}