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

package ru.lazyhat.compukters.compiler.project

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectSnapshotTest {
    @Test
    fun `loader returns strict UTF-8 Kotlin sources in canonical path order`() {
        val root = createTempDirectory("compukter-project-")
        root.resolve("z.kt").writeText("package sample\nval z = 2")
        root.resolve("nested").createDirectories()
        root.resolve("nested/a.kt").writeText("package sample\nval a = 1")
        root.resolve("compukter.toml").writeText("main = \"nested/a.kt\"")

        val snapshot = ProjectSnapshotLoader.load(root, WorkerLimits())

        assertEquals(listOf("nested/a.kt", "z.kt"), snapshot.sources.map { it.path.value })
        assertEquals(
            listOf("package sample\nval a = 1", "package sample\nval z = 2"),
            snapshot.sources.map {
                it.content.toByteArray().decodeToString()
            },
        )
    }

    @Test
    fun `snapshot owns source bytes and source list`() {
        val bytes = "val answer = 42".encodeToByteArray()
        val mutable = mutableListOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(bytes)))
        val snapshot = ProjectSnapshot.of(mutable, WorkerLimits())

        bytes.fill(0)
        mutable.clear()

        assertEquals(1, snapshot.sources.size)
        assertContentEquals(
            "val answer = 42".encodeToByteArray(),
            snapshot.sources
                .single()
                .content
                .toByteArray(),
        )
    }

    @Test
    fun `Kotlin source paths reject host and non-Kotlin paths`() {
        listOf(
            "",
            "/main.kt",
            "C:/main.kt",
            "C:main.kt",
            "//server/share/main.kt",
            "../main.kt",
            "a/./main.kt",
            "a//main.kt",
            "a\\main.kt",
            "main.kts",
            "main.txt",
            "main.kt\u0000",
            "\ud800.kt",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { VirtualSourcePath.kotlin(value) }
        }
        assertEquals("src/main.kt", VirtualSourcePath.kotlin("src/main.kt").value)
        // Diagnostic paths remain protocol-safe but are not limited to Kotlin source suffixes.
        assertEquals("generated/report.txt", VirtualSourcePath.of("generated/report.txt").value)
    }

    @Test
    fun `snapshot rejects empty unordered duplicate invalid UTF-8 and over-limit sources`() {
        val a = source("a.kt", "a")
        val b = source("b.kt", "bb")
        val invalid = ProjectSource(VirtualSourcePath.kotlin("bad.kt"), BinaryValue.of(byteArrayOf(0xc3.toByte(), 0x28)))

        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(emptyList(), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(b, a), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, a), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(invalid), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, b), WorkerLimits(sourceFiles = 1)) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(b), WorkerLimits(sourceFileBytes = 1)) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, b), WorkerLimits(sourceBytes = 2)) }
    }

    @Test
    fun `loader rejects empty invalid UTF-8 symlinks and bounded source input`() {
        val empty = createTempDirectory("compukter-empty-")
        empty.resolve("README.md").writeText("ignored")
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(empty, WorkerLimits()) }

        val invalid = createTempDirectory("compukter-invalid-")
        invalid.resolve("bad.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(invalid, WorkerLimits()) }

        val linked = createTempDirectory("compukter-linked-")
        linked.resolve("main.kt").writeText("val main = 1")
        Files.createSymbolicLink(linked.resolve("alias.kt"), linked.resolve("main.kt"))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(linked, WorkerLimits()) }

        val bounded = createTempDirectory("compukter-bounded-")
        bounded.resolve("a.kt").writeText("aa")
        bounded.resolve("b.kt").writeText("bb")
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceFiles = 1)) }
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceFileBytes = 1)) }
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceBytes = 3)) }
    }

    @Test
    fun `bounded source open does not follow a link presented after validation`() {
        val root = createTempDirectory("compukter-open-link-")
        val outside = root.resolve("outside.txt").also { it.writeText("outside") }
        val source = root.resolve("main.kt")
        Files.createSymbolicLink(source, outside)

        assertFailsWith<Exception> { ProjectSnapshotLoader.readSourceBytes(source, WorkerLimits().sourceFileBytes) }
    }

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))
}
