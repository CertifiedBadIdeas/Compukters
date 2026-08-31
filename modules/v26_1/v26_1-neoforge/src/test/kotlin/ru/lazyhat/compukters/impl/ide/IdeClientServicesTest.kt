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

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdeClientServicesTest {
    @Test
    fun `production analysis timing favors completion without starving presentation`() {
        assertEquals(150_000_000L, ProductionIdeApplicationFactory.analysisTiming.presentationDebounceNanos)
        assertEquals(75_000_000L, ProductionIdeApplicationFactory.analysisTiming.automaticCompletionDebounceNanos)
    }

    @Test
    fun `prepares distinct compiler and analysis workers from one shared bundle`() {
        val gameRoot = createTempDirectory("compukters-ide-tooling-").toAbsolutePath().normalize()
        try {
            val paths = IdeClientPaths.at(gameRoot)
            val prepared = ProductionIdeApplicationFactory.prepare(paths)
            val compiler = prepared.compilerPayload
            val analysis = prepared.analysisPayload
            val guestApi = prepared.analysisBundles.single()

            assertEquals(compiler.root, analysis.root)
            assertNotEquals(compiler.classpath, analysis.classpath)
            assertNotEquals(compiler.manifest.mainClass, analysis.manifest.mainClass)
            assertTrue(
                Path
                    .of(guestApi.classRoot)
                    .fileName
                    .toString()
                    .startsWith("guest-api-core-"),
            )
            assertEquals(guestApi.classRoot, guestApi.sourceRoot)

            val compilerLaunch = ProductionIdeApplicationFactory.compilerLaunch(paths, prepared)
            val analysisLaunch = ProductionIdeApplicationFactory.analysisLaunch(paths, prepared)
            assertEquals(paths.compilerTemporary, compilerLaunch.temporaryDirectory)
            assertEquals(paths.analysisTemporary, analysisLaunch.temporaryDirectory)
            assertNotEquals(compilerLaunch.maximumHeapMiB, analysisLaunch.maximumHeapMiB)
            assertNotEquals(compilerLaunch.maximumMetaspaceMiB, analysisLaunch.maximumMetaspaceMiB)
            assertEquals(compiler.classpath, compilerLaunch.processLaunch(compiler).classpath)
            assertEquals(analysis.classpath, analysisLaunch.classpath)
            assertNotEquals(compilerLaunch.processLaunch(compiler).mainClass, analysisLaunch.mainClass)
        } finally {
            gameRoot.toFile().deleteRecursively()
        }
    }

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
            assertEquals(expected.resolve("workers/tooling"), services.paths.toolingWorkers)
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
