/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.k2

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
                    pluginClasspaths = arrayOf(checkNotNull(System.getProperty("compukters.worker.jar")))
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
