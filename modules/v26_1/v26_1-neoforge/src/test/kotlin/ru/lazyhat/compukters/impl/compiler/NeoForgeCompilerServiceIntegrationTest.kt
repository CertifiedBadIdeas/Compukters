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

package ru.lazyhat.compukters.impl.compiler

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationAddress
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationOutcome
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NeoForgeCompilerServiceIntegrationTest {
    @Test
    fun `packaged worker compiles and persisted world cache reopens`() {
        VmRuntime.requireLoaded()
        val root = createTempDirectory("compukters-production-compiler-").toRealPath()
        try {
            compile(root, token = 1)
            val entries = root.resolve("compukters/compiler-cache/v1")
            assertTrue(Files.list(entries).use { it.count() } > 0)
            compile(root, token = 2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun compile(
        root: java.nio.file.Path,
        token: Long,
    ) {
        NeoForgeCompilerService.open(root).use { compiler ->
            val address = ComputerCompilationAddress(ComputerId.fromLongs(1, token), 1, token)
            assertEquals(
                CompilerSubmissionResult.ACCEPTED,
                compiler.router.submit(
                    address,
                    listOf(VmCompilationSource("/home/hello.kt", "fun main() {}".encodeToByteArray())),
                ),
            )
            repeat(MAXIMUM_POLLS) {
                compiler.router.routeCompletions()
                compiler.router.take(address)?.let { outcome ->
                    if (outcome is ComputerCompilationOutcome.Failure) error(outcome.diagnostics)
                    assertIs<ComputerCompilationOutcome.Success>(outcome, outcome.toString())
                    return
                }
                Thread.sleep(POLL_MILLIS)
            }
            error("compiler did not finish within the test deadline")
        }
    }

    private companion object {
        const val MAXIMUM_POLLS = 3_000
        const val POLL_MILLIS = 10L
    }
}
