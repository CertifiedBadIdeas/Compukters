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

import ru.lazyhat.compukterkraft.scripting.runtime.findJarInJavaClassPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScriptingJarLoaderTest {
    @Test
    fun findsStdlibJarFromJavaClassPath() {
        val root = createTempDirectory("classpath-probe").toFile()

        try {
            val stdlibJarFromClasspath = File(root, "$KOTLIN_STDLIB_JAR_PREFIX.jar").apply { writeText("stub") }
            val resolved =
                findJarInJavaClassPath(
                    classPath = "union:/dev/runtime/example.jar!/${File.pathSeparator}${stdlibJarFromClasspath.absolutePath}",
                    jarPrefix = KOTLIN_STDLIB_JAR_PREFIX,
                )

            assertNotNull(resolved)
            assertEquals(stdlibJarFromClasspath.absoluteFile, resolved.absoluteFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val JAVA_CLASS_PATH_PROPERTY = "java.class.path"
        const val KOTLIN_STDLIB_JAR_PREFIX = "kotlin-stdlib-jdk8"
    }
}
