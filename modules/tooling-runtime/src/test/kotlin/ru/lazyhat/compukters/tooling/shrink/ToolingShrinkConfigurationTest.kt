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

package ru.lazyhat.compukters.tooling.shrink

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolingShrinkConfigurationTest {
    @Test
    fun `renders one stable shrink-only configuration with bounded reports`() {
        val root = Files.createTempDirectory("tooling-shrink-configuration")
        try {
            val inputs = root.resolve("inputs").createDirectories()
            val first = inputs.resolve("z-worker.jar").createFile()
            val second = inputs.resolve("a-runtime.jar").createFile()
            val jmods = root.resolve("jdk/jmods").createDirectories()
            val javaBase = jmods.resolve("java.base.jmod").createFile()
            val javaCompiler = jmods.resolve("java.compiler.jmod").createFile()
            val output = root.resolve("candidate").createDirectories()
            val reports = root.resolve("reports").createDirectories()

            val configuration =
                ToolingShrinkConfiguration.create(
                    inputJars = listOf(first, second),
                    libraryJmods = listOf(javaCompiler, javaBase),
                    outputRoot = output,
                    reportRoot = reports,
                    mainClass = "example.worker.MainKt",
                    whyKeptClasses = listOf("example.worker.MainKt", "example.compiler.Environment"),
                )
            val text = configuration.canonicalText()

            assertEquals(1, text.lineSequence().count { it.startsWith("-injars ") && "a-runtime.jar" in it })
            assertEquals(1, text.lineSequence().count { it.startsWith("-injars ") && "z-worker.jar" in it })
            assertTrue(text.indexOf("a-runtime.jar") < text.indexOf("z-worker.jar"))
            assertTrue(text.indexOf("java.base.jmod") < text.indexOf("java.compiler.jmod"))
            assertTrue("-dontoptimize\n" in text)
            assertTrue("-dontobfuscate\n" in text)
            assertTrue("-dontpreverify\n" in text)
            assertTrue("-keep public class example.worker.MainKt" in text)
            assertTrue("public static void main(java.lang.String[]);" in text)
            assertTrue("-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*,MethodParameters,SourceFile,LineNumberTable" in text)
            assertTrue("-keep class kotlin.Metadata" in text)
            assertTrue("-printusage" in text && "usage.txt" in text)
            assertTrue("-printseeds" in text && "seeds.txt" in text)
            assertEquals(2, text.lineSequence().count { it.startsWith("-whyareyoukeeping class ") })
            assertFalse("-ignorewarnings" in text)
            assertFalse("-dontwarn" in text)
            assertFalse("-keep class org.jetbrains.kotlin.**" in text)
            assertFalse("-keep class com.intellij.**" in text)
            assertTrue(text.endsWith('\n'))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects duplicate inputs and outputs outside their declared roots`() {
        val root = Files.createTempDirectory("tooling-shrink-invalid")
        try {
            val input = root.resolve("worker.jar").createFile()
            val jmod = root.resolve("java.base.jmod").createFile()
            val output = root.resolve("candidate").createDirectories()
            val reports = root.resolve("reports").createDirectories()

            assertFailsWith<IllegalArgumentException> {
                ToolingShrinkConfiguration.create(
                    inputJars = listOf(input, input),
                    libraryJmods = listOf(jmod),
                    outputRoot = output,
                    reportRoot = reports,
                    mainClass = "example.MainKt",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ToolingShrinkConfiguration.create(
                    inputJars = listOf(input),
                    libraryJmods = listOf(jmod),
                    outputRoot = output,
                    reportRoot = reports,
                    mainClass = "not a binary name",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ToolingShrinkConfiguration(
                    inputJars = listOf(input),
                    libraryJmods = listOf(jmod),
                    outputJar = root.resolve("outside.jar"),
                    outputRoot = output,
                    usageReport = reports.resolve("usage.txt"),
                    seedsReport = reports.resolve("seeds.txt"),
                    reportRoot = reports,
                    mainClass = "example.MainKt",
                    whyKeptClasses = emptyList(),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
