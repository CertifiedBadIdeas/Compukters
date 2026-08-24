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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession

internal interface ProgramVmSession : AutoCloseable {
    fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
    ): VmOutcome

    fun resume(
        requestId: Long,
        response: HostResponse,
    )

    fun completeCompilationArtifact(
        token: Long,
        artifact: ByteArray,
    )

    fun completeCompilationFailure(
        token: Long,
        diagnostics: String,
    )

    fun commitTerminal()

    fun terminalFullState(): TerminalState

    fun terminalChangesSince(revision: Long): TerminalUpdate

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    )

    fun sendTerminalText(value: String)

    fun filesystemGeneration(): Long
}

internal fun interface ProgramVmSessionFactory {
    fun open(artifact: ByteArray): ProgramVmSession

    fun boot(): ProgramVmSession = throw VmBridgeException("ROM boot is not configured")
}

internal class ProgramFileSystemLaunchContext(
    val store: WorldFileSystemStore,
    val computerId: ComputerId,
    romImage: ByteArray,
) {
    private val romImage = romImage.copyOf()

    fun open(artifact: ByteArray): VmSession = VmSession.openInStore(artifact, store, computerId, romImage.copyOf())

    fun boot(): VmSession = VmSession.bootInStore(store, computerId, romImage.copyOf())
}

internal class NativeProgramVmSessionFactory(
    private val filesystem: ProgramFileSystemLaunchContext? = null,
) : ProgramVmSessionFactory {
    override fun open(artifact: ByteArray): ProgramVmSession =
        NativeProgramVmSession(filesystem?.open(artifact) ?: VmSession.open(artifact))

    override fun boot(): ProgramVmSession {
        val context = filesystem ?: throw VmBridgeException("ROM boot requires a persistent filesystem")
        return NativeProgramVmSession(context.boot())
    }
}

private class NativeProgramVmSession(
    private val session: VmSession,
) : ProgramVmSession {
    override fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
    ): VmOutcome = session.advance(guestBudget, maintenanceBudget)

    override fun resume(
        requestId: Long,
        response: HostResponse,
    ) = session.resume(requestId, response)

    override fun completeCompilationArtifact(
        token: Long,
        artifact: ByteArray,
    ) = session.completeCompilationArtifact(token, artifact)

    override fun completeCompilationFailure(
        token: Long,
        diagnostics: String,
    ) = session.completeCompilationFailure(token, diagnostics)

    override fun commitTerminal() = session.commitTerminal()

    override fun terminalFullState(): TerminalState = session.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate = session.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) = session.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String) = session.sendTerminalText(value)

    override fun filesystemGeneration(): Long = session.filesystemGeneration()

    override fun close() = session.close()
}
