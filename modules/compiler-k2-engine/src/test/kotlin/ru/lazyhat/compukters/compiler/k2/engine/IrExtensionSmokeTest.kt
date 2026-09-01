/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrExtensionSmokeTest {
    @Test
    fun `pinned K2 loads only the worker plugin and reaches typed script IR`() {
        val root = createTempDirectory("compukters-k2-ir-test-")
        try {
            val source = root.resolve("project/main.kt")
            source.parent.createDirectories()
            source.writeText("val answer: Int = 42")
            val output = root.resolve("classes")
            output.createDirectories()
            val modules = mutableListOf<IrModuleFragment>()
            val arguments =
                K2JVMCompilerArguments().apply {
                    freeArgs = listOf(source.toString())
                    destination = output.toString()
                    moduleName = "compukter-script"
                    languageVersion = "2.4"
                    apiVersion = "2.4"
                    jvmTarget = "17"
                    noReflect = true
                    pluginClasspaths = arrayOf(checkNotNull(System.getProperty("compukters.engine.jar")))
                }

            val exitCode =
                CompilationBridge.withSession(CompilationSession(irSink = { module, _ -> modules += module })) {
                    K2JVMCompiler().exec(MessageCollector.NONE, Services.EMPTY, arguments)
                }

            assertEquals(ExitCode.OK, exitCode)
            assertEquals(1, modules.size)
            val facts = IrFacts().also { modules.single().accept(it, null) }
            assertTrue(facts.sourceFiles.any { it.replace('\\', '/').endsWith("/project/main.kt") })
            assertTrue(42 in facts.intConstants, "typed IR must contain the Int constant 42")
            assertFailsWith<IllegalStateException> { CompilationBridge.requireSession() }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private class IrFacts : IrVisitorVoid() {
    val sourceFiles = mutableListOf<String>()
    val intConstants = mutableListOf<Int>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitFile(declaration: IrFile) {
        sourceFiles += declaration.fileEntry.name
        super.visitFile(declaration)
    }

    override fun visitConst(expression: IrConst) {
        (expression.value as? Int)?.let(intConstants::add)
        super.visitConst(expression)
    }
}
