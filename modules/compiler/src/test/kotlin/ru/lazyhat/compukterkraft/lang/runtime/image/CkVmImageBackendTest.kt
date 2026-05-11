/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CkVmImageBackendTest {
    @Test
    fun compileImageCreatesRegisterEntryFunctionForEmptyMain() {
        val artifact = LanguageFrontend().compileImage("main.ck", "pub fun main() { }")
        val image = assertNotNull(artifact.image)
        val function = image.functions.single()

        assertEquals("ckl-1", image.languageVersion)
        assertEquals(0, image.entryFunctionIndex)
        assertEquals("main.ck#main", function.name)
        assertEquals(0, function.parameterCount)
        assertEquals(
            listOf(
                CkVmInstruction.LoadUnit(0),
                CkVmInstruction.Return(0),
            ),
            function.instructions,
        )
    }

    @Test
    fun compileImageLowersIntegerArithmeticIntoTypedRegisters() {
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val value: Int = 1 + 2 * 3;
                        }
                        """.trimIndent(),
                    ).image,
            )
        val mainFunction = image.functions.single()

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(1),
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(3),
            ),
            image.constants,
        )
        assertTrue(mainFunction.i32RegisterCount > mainFunction.parameters.size)
        assertTrue(mainFunction.boolRegisterCount >= 0)
        assertTrue(mainFunction.refRegisterCount >= 0)
        assertTrue(mainFunction.instructions.any { instruction -> instruction is CkVmInstruction.I32Mul })
        assertTrue(mainFunction.instructions.any { instruction -> instruction is CkVmInstruction.I32Add })
        assertTrue(mainFunction.instructions.any { instruction -> instruction is CkVmInstruction.Move && instruction.dst == 0 })
    }

    @Test
    fun compileImageLowersHostCallToDeclaredImportId() {
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }")
                    .image,
            )
        val call = image.functions.single().instructions.filterIsInstance<CkVmInstruction.CallHost>().single()

        assertEquals(listOf(CkVmConstant.StringConstant("hi")), image.constants)
        assertEquals(listOf(CkVmHostImport(3004, "system", "log", listOf("String"), "Unit")), image.hostImports)
        assertEquals(3004, call.importId)
        assertEquals(1, call.arguments.size)
        assertNotNull(call.returnRegister)
    }

    @Test
    fun compileImageLowersControlFlowToRegisterInstructionTargets() {
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            while (false) {
                                yield();
                            }
                        }
                        """.trimIndent(),
                    ).image,
            )
        val mainFunction = image.functions.single()
        val jumpIfFalse = mainFunction.instructions.filterIsInstance<CkVmInstruction.JumpIfFalse>().single()
        val backwardJump = mainFunction.instructions.filterIsInstance<CkVmInstruction.Jump>().single()

        assertTrue(jumpIfFalse.target in mainFunction.instructions.indices)
        assertEquals(0, backwardJump.target)
        assertTrue(mainFunction.instructions.any { instruction -> instruction is CkVmInstruction.Yield })
    }

    @Test
    fun compileImageLowersUserFunctionCall() {
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
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
        val addFunction = image.functions.single { it.name == "main.ck#add" }
        val mainFunction = image.functions.single { it.name == "main.ck#main" }
        val call = mainFunction.instructions.filterIsInstance<CkVmInstruction.CallStatic>().single()

        assertTrue(addIndex >= 0)
        assertEquals(2, addFunction.parameterCount)
        assertTrue(addFunction.instructions.any { instruction -> instruction is CkVmInstruction.I32Add })
        assertEquals(addIndex, call.functionIndex)
        assertEquals(2, call.arguments.size)
    }

    @Test
    fun compileImageReturnsNullImageWhenFrontendHasErrors() {
        val artifact = LanguageFrontend().compileImage("main.ck", "fun main() { }")

        assertNull(artifact.image)
        assertTrue(
            artifact.bytecode.analysis.diagnostics
                .any { it.severity == FrontendSeverity.ERROR },
        )
    }

    @Test
    fun unsupportedInstructionReportsClearError() {
        val base = assertNotNull(LanguageFrontend().compile("main.ck", "pub fun main() { }").module)
        val function =
            base.functions.single().copy(
                instructions = listOf(Instruction.ConstructClass("Box", emptyList()), Instruction.Return),
            )

        val error =
            assertFailsWith<UnsupportedOperationException> {
                CkVmImageCompiler.compile(base.copy(functions = listOf(function)))
            }

        assertTrue(error.message.orEmpty().contains("CkVmImage register backend does not support ConstructClass"))
    }

    @Test
    fun writesBackendFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.image.backend.fixture.path")?.takeIf(String::isNotBlank) ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)

        java.nio.file.Files
            .createDirectories(
                java.nio.file.Path
                    .of(path)
                    .parent,
            )
        java.nio.file.Files
            .write(
                java.nio.file.Path
                    .of(path),
                CkVmImageAbi.encode(image),
            )
    }
}
