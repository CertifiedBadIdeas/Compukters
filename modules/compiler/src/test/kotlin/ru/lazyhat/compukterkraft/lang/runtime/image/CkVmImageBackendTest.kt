package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.api.Instruction
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
        assertEquals(listOf(CkVmHostImport(3004, "system", "log", listOf("String"), "Unit")), image.hostImports)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
            CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersBoolAndLocalSlots() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val enabled: Bool = true;
                    if (enabled) {
                        system::log("yes");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertEquals(1, image.functions.single().frameSize)
        assertEquals(listOf(CkVmConstant.StringConstant("yes")), image.constants)
        assertEquals(listOf(CkVmHostImport(3004, "system", "log", listOf("String"), "Unit")), image.hostImports)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_BOOL, 1,
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 37, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.JUMP, 37, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersPushNullFromBytecodeModule() {
        val base = assertNotNull(LanguageFrontend().compile("main.ck", "pub fun main() { }").module)
        val function = base.functions.single().copy(instructions = listOf(Instruction.PushNull, Instruction.Return))

        val image = CkVmImageCompiler.compile(base.copy(functions = listOf(function)))

        assertContentEquals(
            listOf(CkVmImageOpcodes.PUSH_NULL, CkVmImageOpcodes.RETURN),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersForwardAndBackwardJumpsToByteOffsets() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    while (false) {
                        system::log("loop");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_BOOL, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 27, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.JUMP, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersBinaryAndUnaryOperators() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val value: Int = 1 + 2 * 3;
                    val ok: Bool = value >= 7 && !false;
                    if (ok) {
                        system::log("ok");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(1),
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(3),
                CkVmConstant.IntConstant(7),
                CkVmConstant.StringConstant("ok"),
            ),
            image.constants,
        )
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 2, 0, 0, 0,
                CkVmImageOpcodes.BINARY, 2,
                CkVmImageOpcodes.BINARY, 0,
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 3, 0, 0, 0,
                CkVmImageOpcodes.BINARY, 9,
                CkVmImageOpcodes.PUSH_BOOL, 0,
                CkVmImageOpcodes.UNARY, 1,
                CkVmImageOpcodes.BINARY, 10,
                CkVmImageOpcodes.STORE_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 77, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 4, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.JUMP, 77, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersUserFunctionCall() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                fun add(a: Int, b: Int): Int {
                    return a + b;
                }

                pub fun main() {
                    val result: Int = add(2, 5);
                }
                """.trimIndent(),
            ).image,
        )
        val addIndex = image.functions.indexOfFirst { it.name == "main.ck#add" }
        val mainFunction = image.functions.single { it.name == "main.ck#main" }
        val addFunction = image.functions.single { it.name == "main.ck#add" }

        assertTrue(addIndex >= 0)
        assertEquals(2, addFunction.frameSize)
        assertEquals(1, mainFunction.frameSize)
        assertEquals(listOf(CkVmConstant.IntConstant(2), CkVmConstant.IntConstant(5)), image.constants)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.BINARY, 0,
                CkVmImageOpcodes.RETURN,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            addFunction.code,
        )
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.CALL_FUNCTION,
            ) + i32(addIndex) + i32(2) + listOf(
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            mainFunction.code,
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
        val artifact = LanguageFrontend().compile(
            "main.ck",
            """
            struct Box { value: Int }
            pub fun main() { val box: Box = Box(value = 1); }
            """.trimIndent(),
        )
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support ConstructRecord"))
    }

    private fun i32(value: Int): List<Int> =
        listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)

    @Test
    fun writesBackendFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.image.backend.fixture.path")?.takeIf(String::isNotBlank) ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)

        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
        java.nio.file.Files.write(java.nio.file.Path.of(path), CkVmImageAbi.encode(image))
    }
}