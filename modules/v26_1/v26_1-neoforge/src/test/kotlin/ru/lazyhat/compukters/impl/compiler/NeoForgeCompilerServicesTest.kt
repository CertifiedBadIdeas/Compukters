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

package ru.lazyhat.compukters.impl.compiler

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class NeoForgeCompilerServicesTest {
    @Test
    fun `canonical world root owns one shared service until stop`() {
        val root = createTempDirectory("compukters-compiler-services-").toRealPath()
        val secondRoot = root.resolve("second").createDirectories().toRealPath()
        val opened = mutableListOf<Path>()
        val registry = CompilerServiceRegistry { path -> FakeService(path).also { opened.add(path) } }
        try {
            val first = registry.service(root)
            assertSame(first, registry.service(root.resolve(".")))
            val second = registry.service(secondRoot)
            assertNotSame(first, second)
            assertEquals(listOf(root, secondRoot), opened)

            registry.stop(root)
            registry.stop(root)
            assertEquals(1, first.closes)
            val reopened = registry.service(root)
            assertNotSame(first, reopened)
            assertEquals(root, reopened.root)
        } finally {
            registry.stop(root)
            registry.stop(secondRoot)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `service paths keep cache payload and temporary storage separate`() {
        val root = createTempDirectory("compukters-compiler-paths-").toRealPath()
        try {
            val paths = CompilerServicePaths.at(root)
            assertEquals(root.resolve("compukters/compiler-cache").normalize(), paths.cacheRoot)
            assertEquals(root.resolve("compukters/compiler-worker").normalize(), paths.payloadRoot)
            assertEquals(root.resolve("compukters/compiler-temp").normalize(), paths.temporaryRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class FakeService(
        val root: Path,
    ) : AutoCloseable {
        var closes = 0

        override fun close() {
            closes++
        }
    }
}
