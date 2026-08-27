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

package ru.lazyhat.compukters.impl.ide

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeClientServicesTest {
    @Test
    fun `uses distinct bounded client roots and opens only one session`() {
        val gameRoot = createTempDirectory("compukters-ide-services-").toAbsolutePath().normalize()
        val opened = mutableListOf<RecordingApplication>()
        val lifetime = RecordingLifetime()
        try {
            val services = IdeClientServices(gameRoot, lifetime) { paths -> RecordingApplication(paths).also(opened::add) }
            val expected = gameRoot.resolve("compukters/ide")
            assertEquals(expected.resolve("projects"), services.paths.projects)
            assertEquals(expected.resolve("cache/compiler"), services.paths.compilerCache)
            assertEquals(expected.resolve("workers/compiler"), services.paths.compilerWorkers)
            assertEquals(expected.resolve("workers/analysis"), services.paths.analysisWorkers)
            assertEquals(expected.resolve("tmp/compiler"), services.paths.compilerTemporary)
            assertEquals(expected.resolve("tmp/analysis"), services.paths.analysisTemporary)
            assertEquals(expected.resolve("session.preferences"), services.paths.preferences)

            val first = services.open()
            assertEquals(services.paths, first.application.paths)
            assertFailsWith<IllegalStateException> { services.open() }
            first.close()
            assertTrue(opened.single().closed)

            val second = services.open()
            assertEquals(2, opened.size)
            assertFalse(opened.last().closed)
            services.close()
            assertTrue(opened.last().closed)
            assertTrue(lifetime.closed)
            second.close()
        } finally {
            gameRoot.toFile().deleteRecursively()
        }
    }
}

private class RecordingLifetime : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
    }
}

private class RecordingApplication(
    val paths: IdeClientPaths,
) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
    }
}
