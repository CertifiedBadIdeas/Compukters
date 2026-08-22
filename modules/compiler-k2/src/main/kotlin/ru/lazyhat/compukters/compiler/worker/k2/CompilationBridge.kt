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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path

fun interface CompilationIrSink {
    fun accept(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
    )
}

class CompilationSession(
    val irSink: CompilationIrSink,
    val artifactSink: (BinaryValue) -> Unit = {},
    val diagnosticSink: (WorkerDiagnostic) -> Unit = {},
    sourcePaths: Map<String, VirtualSourcePath> = emptyMap(),
    val limits: WorkerLimits = WorkerLimits(),
) {
    private val sourcePaths = sourcePaths.mapKeys { (path, _) -> normalize(path) }

    fun virtualSourcePath(physicalPath: String?): VirtualSourcePath? = physicalPath?.let { sourcePaths[normalize(it)] }

    private fun normalize(path: String): String =
        runCatching {
            Path
                .of(path)
                .toAbsolutePath()
                .normalize()
                .toString()
        }.getOrDefault(path)
}

object CompilationBridge {
    private val active = ThreadLocal<CompilationSession?>()

    fun <T> withSession(
        session: CompilationSession,
        action: () -> T,
    ): T {
        check(active.get() == null) { "a compiler session is already active on this thread" }
        active.set(session)
        return try {
            action()
        } finally {
            active.remove()
        }
    }

    internal fun requireSession(): CompilationSession =
        checkNotNull(active.get()) { "K2 IR extension ran without an active compilation session" }
}
