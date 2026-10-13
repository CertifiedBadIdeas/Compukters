/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.compiler

import compukter.filesystem.Path

public object Compiler {
    /** Compiles a source snapshot and atomically installs the executable at the normalized output. */
    public fun compile(
        source: Path,
        output: Path,
    ): CompilationResult {
        if (source.value.length > 4096 || output.value.length > 4096) {
            return CompilationResult.Failed(CompilationFailureReason.INVALID_PATH, "path exceeds the transport limit")
        }
        val canonicalSource = source.normalize()
        val canonicalOutput = output.normalize()
        if (canonicalSource.value.length + canonicalOutput.value.length > 4096) {
            return CompilationResult.Failed(CompilationFailureReason.LIMIT_EXCEEDED, "compilation paths exceed the transport limit")
        }
        val reply = CompilerBindings.compile(canonicalSource.value, canonicalOutput.value)
        if (reply.isEmpty()) throw IllegalStateException("empty compiler reply")
        val status = reply[0].code
        if (status == 0) return CompilationResult.Success(canonicalOutput)
        val reason =
            when (status) {
                1 -> CompilationFailureReason.REJECTED
                2 -> CompilationFailureReason.STALE
                3 -> CompilationFailureReason.INVALID_ARTIFACT
                4 -> CompilationFailureReason.IO_FAILURE
                else -> throw IllegalStateException("invalid compiler status")
            }
        return CompilationResult.Failed(reason, reply.substring(1, reply.length))
    }
}

public sealed interface CompilationResult {
    public value class Success(
        val output: Path,
    ) : CompilationResult

    public value class Failed(
        val reason: CompilationFailureReason,
        val diagnostics: String,
    ) : CompilationResult
}

public enum class CompilationFailureReason {
    INVALID_PATH,
    LIMIT_EXCEEDED,
    REJECTED,
    STALE,
    INVALID_ARTIFACT,
    IO_FAILURE,
}

private object CompilerBindings {
    external fun compile(
        source: String,
        output: String,
    ): String
}
