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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.LibraryPreparationResult
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildFailureKind
import ru.lazyhat.compukters.ide.client.build.IdeBuildServices
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeBuildFlowTest {
    @Test
    fun `late configuration from an earlier preparation is ignored`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.workspace.installLock(canonicalLock())
        val oldRead = CompletableFuture<ru.lazyhat.compukters.ide.client.workspace.IdeProjectConfiguration>()
        fixture.workspace.configurationBarrier = oldRead
        fixture.startAndTick()
        assertTrue(compilation.preparations.isEmpty())
        fixture.workspace.configurationBarrier = null
        fixture.controller.dispatch(IdeCommand.Resolve)
        fixture.tickUntil { compilation.preparations.isNotEmpty() }
        val root = fixture.workspace.descriptor.handle.canonicalPath
        oldRead.complete(
            ru.lazyhat.compukters.ide.client.workspace.IdeProjectConfiguration(
                fixture.workspace.descriptor.handle,
                root.resolve("compukter.toml").toFile().readBytes(),
                canonicalLock(),
            ),
        )
        repeat(5) { fixture.controller.tick() }
        assertEquals(1, compilation.preparations.size)
        assertEquals(1, fixture.workspace.buildInputRequests, "only explicit Resolve may read build input")
        fixture.controller.close()
    }

    @Test
    fun `opening a resolved project prepares libraries without reading sources or changing build state`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.workspace.installLock(canonicalLock())
        fixture.startAndTick()
        fixture.tickUntil { compilation.preparations.isNotEmpty() }
        assertEquals(0, fixture.workspace.buildInputRequests)
        assertTrue(fixture.workspace.configurationRequests > 0)
        assertIs<IdeBuildState.Idle>(fixture.workspaceView().build)
        assertTrue(compilation.inputs.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `unresolved project skips preparation and resolve schedules it quietly`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.startAndTick()
        repeat(5) { fixture.controller.tick() }
        assertTrue(compilation.preparations.isEmpty())
        assertIs<IdeBuildState.Idle>(fixture.workspaceView().build)
        fixture.controller.dispatch(IdeCommand.Resolve)
        fixture.tickUntil { compilation.preparations.isNotEmpty() }
        assertTrue(compilation.inputs.isEmpty())
        assertEquals("Created compukter.lock", fixture.workspaceView().status?.message)
        fixture.controller.close()
    }

    @Test
    fun `build saves visible editor revision before loading canonical build input`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.workspace.installLock(canonicalLock())
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("// saved")))

        fixture.controller.dispatch(IdeCommand.Build)
        assertIs<IdeBuildState.Saving>(fixture.workspaceView().build)
        assertEquals(0, fixture.workspace.buildInputRequests)
        assertEquals(1, fixture.workspace.saveRequests.size)

        fixture.workspace.completeSave()
        fixture.controller.tick()
        assertEquals(1, fixture.workspace.buildInputRequests)
        fixture.controller.tick()
        val submitted = compilation.awaitInput()
        fixture.controller.tick()
        val compiling = assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)
        assertTrue(
            submitted.sources.sources
                .single()
                .content
                .toByteArray()
                .decodeToString()
                .contains("// saved"),
        )

        compilation.complete(
            ClientBuildResult.Success(compiling.identity, BinaryValue.of(byteArrayOf(1)), buildHash(8), cacheHit = false),
        )
        fixture.tickUntil { fixture.workspaceView().build is IdeBuildState.Succeeded }
        assertEquals(buildHash(8), assertIs<IdeBuildState.Succeeded>(fixture.workspaceView().build).artifactHash)
        fixture.controller.close()
    }

    @Test
    fun `save conflict stops build before snapshot and compiler`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.workspace.installLock(canonicalLock())
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("dirty")))
        fixture.controller.dispatch(IdeCommand.Build)

        fixture.workspace.completeSaveConflict()
        fixture.controller.tick()

        val failed = assertIs<IdeBuildState.Failed>(fixture.workspaceView().build)
        assertEquals(IdeBuildFailureKind.Conflict, failed.kind)
        assertEquals(0, fixture.workspace.buildInputRequests)
        assertTrue(compilation.inputs.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `resolve creates missing lock and confirms replacement of existing lock`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.startAndTick()

        fixture.controller.dispatch(IdeCommand.Resolve)
        fixture.tickUntil {
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("compukter.lock")
                .toFile()
                .exists()
        }
        fixture.tickUntil { fixture.workspaceView().status?.message == "Created compukter.lock" }

        val canonical =
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("compukter.lock")
                .toFile()
                .readBytes()
        val stale = ProjectLockCodec.encode(ProjectLock.of(toolchain().copy(compilerVersion = "old"), emptyList())).encodeToByteArray()
        fixture.workspace.installLock(stale)
        fixture.controller.dispatch(IdeCommand.Resolve)
        fixture.tickUntil { fixture.controller.viewState().dialog is IdeDialogState.LockUpdate }
        assertContentEquals(
            stale,
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("compukter.lock")
                .toFile()
                .readBytes(),
        )

        fixture.controller.dispatch(IdeCommand.ConfirmLockUpdate)
        fixture.tickUntil {
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("compukter.lock")
                .toFile()
                .readBytes()
                .contentEquals(canonical)
        }
        fixture.controller.close()
    }

    @Test
    fun `close cancels active compilation and late result cannot be observed`() {
        val compilation = FlowCompilationService()
        val fixture = fixture(compilation)
        fixture.workspace.installLock(canonicalLock())
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Build)
        fixture.controller.tick()
        compilation.awaitInput()
        fixture.controller.tick()

        fixture.controller.close()

        compilation.awaitCancellation()
        assertEquals(1, compilation.cancelCalls)
        compilation.complete(
            ClientBuildResult.Success(
                assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build).identity,
                BinaryValue.of(byteArrayOf(1)),
                buildHash(7),
                cacheHit = false,
            ),
        )
        assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)
    }

    private fun fixture(compilation: FlowCompilationService): ControllerFixture =
        ControllerFixture(preferences("demo", "src/main.kt")) { workspace, clock ->
            IdeBuildCoordinator(
                IdeBuildServices(
                    TEST_PROJECT_RESOLUTION,
                    CompileProfileResolver(toolchain(), TEST_PLATFORM_CATALOG, BUILD_LIMITS),
                    { project -> ProjectLockService(project.lockFileWriter()) },
                    compilation,
                ),
                clock,
            )
        }

    private fun ControllerFixture.tickUntil(predicate: () -> Boolean) {
        repeat(500) {
            controller.tick()
            if (predicate()) return
            Thread.sleep(10)
        }
        error("condition was not reached")
    }

    private fun canonicalLock(): ByteArray = ProjectLockCodec.encode(ProjectLock.of(toolchain(), emptyList())).encodeToByteArray()

    companion object {
        private val BUILD_LIMITS = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
    }
}

private class FlowCompilationService : ClientCompilationService {
    val preparations = LinkedBlockingQueue<CompileProfile>()
    val inputs = mutableListOf<ClientBuildSnapshot>()
    private val submitted = LinkedBlockingQueue<ClientBuildSnapshot>()
    private val futures = LinkedBlockingQueue<CompletableFuture<ClientBuildResult>>()
    private val cancelled = CompletableFuture<Unit>()
    var cancelCalls = 0

    override fun prepareLibraries(profile: CompileProfile): CompletableFuture<LibraryPreparationResult> =
        CompletableFuture
            .completedFuture<LibraryPreparationResult>(
                ru.lazyhat.compukters.compiler.worker.protocol.LibrariesPrepared(
                    ru.lazyhat.compukters.compiler.worker.protocol.RequestId
                        .of(1uL),
                    ru.lazyhat.compukters.compiler.worker.protocol
                        .CompilationMetrics(1uL, 2uL, 3uL),
                ),
            ).also { preparations.add(profile) }

    override fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult> {
        synchronized(inputs) { inputs += input }
        val future = CompletableFuture<ClientBuildResult>()
        futures.add(future)
        submitted.add(input)
        return future
    }

    fun awaitInput(): ClientBuildSnapshot = requireNotNull(submitted.poll(5, TimeUnit.SECONDS))

    fun awaitCancellation() = cancelled.get(5, TimeUnit.SECONDS)

    fun complete(result: ClientBuildResult) {
        futures.remove().complete(result)
    }

    override fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean {
        cancelCalls++
        cancelled.complete(Unit)
        return true
    }

    override fun close() = Unit
}

private fun toolchain() = TEST_TOOLCHAIN

private fun buildHash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })
