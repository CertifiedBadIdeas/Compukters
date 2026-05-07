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
    fun compileImageLowersRecordConstructionAndFieldAccess() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                struct Point { x: Int, y: Int }

                pub fun main() {
                    val point: Point = Point(x = 2, y = 5);
                    val delta: Int = point.x - point.y;
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(5),
                CkVmConstant.StringConstant("Point"),
                CkVmConstant.StringConstant("x"),
                CkVmConstant.StringConstant("y"),
            ),
            image.constants,
        )
        assertEquals(2, mainFunction.frameSize)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.CONSTRUCT_RECORD,
            ) + i32(2) + i32(2) + i32(3) + i32(4) + listOf(
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.GET_FIELD,
            ) + i32(3) + listOf(
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.GET_FIELD,
            ) + i32(4) + listOf(
                CkVmImageOpcodes.BINARY, 1,
                CkVmImageOpcodes.STORE_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            mainFunction.code,
        )
    }

    @Test
    fun compileImageLowersCollectionConstructorsAndIndexOps() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val array: Array<Int> = Array<Int>(size = 2, default = 0);
                    array[1] = 7;
                    val arrayValue: Int = array[1];
                    val list: List<Int> = [2, 5];
                    val listValue: Int = list[0] - list[1];
                    val map: Map<String, Int> = {"x": 3};
                    map["y"] = 4;
                    val mapValue: Int? = map["missing"];
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(0),
                CkVmConstant.IntConstant(1),
                CkVmConstant.IntConstant(7),
                CkVmConstant.IntConstant(5),
                CkVmConstant.StringConstant("x"),
                CkVmConstant.IntConstant(3),
                CkVmConstant.StringConstant("y"),
                CkVmConstant.IntConstant(4),
                CkVmConstant.StringConstant("missing"),
            ),
            image.constants,
        )
        assertEquals(6, mainFunction.frameSize)
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_ARRAY))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_LIST))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_MAP))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.INDEX_GET))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.INDEX_SET))
    }

    @Test
    fun compileImageLowersCollectionMethodsWithStringMetadata() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val list: List<Int> = [2];
                    list.add(5);
                    val size: Int = list.size();
                    val removed: Int = list.removeAt(0);
                    val map: Map<String, Int> = {"x": 1};
                    val exists: Bool = map.containsKey("x");
                    val fallback: Int = map.getOrDefault("missing", 9);
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertTrue(image.constants.contains(CkVmConstant.StringConstant("add")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("size")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("removeAt")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("containsKey")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("getOrDefault")))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CALL_COLLECTION_METHOD))
    }

    @Test
    fun compileImageReturnsNullImageWhenFrontendHasErrors() {
        val artifact = LanguageFrontend().compileImage("main.ck", "fun main() { }")

        assertNull(artifact.image)
        assertTrue(artifact.bytecode.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR })
    }

    @Test
    fun unsupportedInstructionReportsClearError() {
        val base = assertNotNull(LanguageFrontend().compile("main.ck", "pub fun main() { }").module)
        val function = base.functions.single().copy(instructions = listOf(Instruction.ConstructClass("Box", emptyList()), Instruction.Return))

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(base.copy(functions = listOf(function)))
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support ConstructClass"))
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