/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.core.device.runtime.compiler

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import java.util.Collections

data class ComputerCompilationAddress(
    val computerId: ComputerId,
    val vmEpoch: Long,
    val token: Long,
) {
    init {
        require(vmEpoch > 0) { "VM epoch must be positive" }
        require(token > 0) { "compilation token must be positive" }
    }
}

class ComputerCompilationRequest(
    val address: ComputerCompilationAddress,
    sources: List<VmCompilationSource>,
) {
    val sources: List<VmCompilationSource> =
        Collections.unmodifiableList(
            sources.map { source -> VmCompilationSource(source.path, source.utf8Bytes()) },
        )

    init {
        require(this.sources.isNotEmpty()) { "compilation request must contain sources" }
    }
}

data class ComputerCompilerCompletion(
    val address: ComputerCompilationAddress,
    val outcome: ComputerCompilationOutcome,
)

sealed interface ComputerCompilationOutcome {
    class Success(
        artifact: ByteArray,
    ) : ComputerCompilationOutcome {
        private val artifact = artifact.copyOf()

        init {
            require(this.artifact.size <= MAXIMUM_ARTIFACT_BYTES) { "compiler artifact exceeds FFM limit" }
        }

        fun artifactBytes(): ByteArray = artifact.copyOf()

        override fun equals(other: Any?): Boolean = other is Success && artifact.contentEquals(other.artifact)

        override fun hashCode(): Int = artifact.contentHashCode()

        override fun toString(): String = "Success(artifactBytes=${artifact.size})"
    }

    class Failure(
        val diagnostics: String,
    ) : ComputerCompilationOutcome {
        private val diagnosticBytes = diagnostics.encodeToByteArray().size

        init {
            require(diagnosticBytes <= MAXIMUM_DIAGNOSTIC_BYTES) { "compiler diagnostics exceed FFM limit" }
        }

        override fun equals(other: Any?): Boolean = other is Failure && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()

        override fun toString(): String = "Failure(diagnosticBytes=$diagnosticBytes)"
    }

    private companion object {
        const val MAXIMUM_ARTIFACT_BYTES = 16 * 1024 * 1024
        const val MAXIMUM_DIAGNOSTIC_BYTES = 64 * 1024
    }
}

interface ComputerCompiler {
    fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult

    fun drain(maximum: Int): List<ComputerCompilerCompletion>

    fun cancel(address: ComputerCompilationAddress): Boolean
}
