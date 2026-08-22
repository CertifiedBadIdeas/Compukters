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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

data class WorkerPayloadLoadLimits(
    val manifestBytes: Int = 1024 * 1024,
    val files: Int = 128,
    val payloadBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(manifestBytes >= 0) { "manifest byte limit must not be negative" }
        require(files >= 0) { "payload file limit must not be negative" }
        require(payloadBytes >= 0) { "payload byte limit must not be negative" }
    }
}

object WorkerPayloadLoader {
    fun load(
        root: Path,
        limits: WorkerPayloadLoadLimits = WorkerPayloadLoadLimits(),
    ): PublishedWorkerPayload =
        try {
            loadChecked(root.toAbsolutePath().normalize(), limits)
        } catch (exception: WorkerPayloadException) {
            throw exception
        } catch (exception: Exception) {
            throw WorkerPayloadException("published worker payload is invalid", exception)
        }

    private fun loadChecked(
        root: Path,
        limits: WorkerPayloadLoadLimits,
    ): PublishedWorkerPayload {
        requireRegularDirectory(root)
        val manifestPath = root.resolve(MANIFEST_FILE)
        val text = decodeManifest(readBoundedRegularFile(manifestPath, limits.manifestBytes))
        val parsed = parseManifest(text, limits)
        val classpath = parsed.files.map { file -> validatePayloadFile(root, file) }
        return PublishedWorkerPayload(root, parsed, classpath)
    }

    private fun parseManifest(
        text: String,
        limits: WorkerPayloadLoadLimits,
    ): WorkerPayloadManifest {
        if (!text.endsWith('\n') || '\r' in text) invalid("payload manifest is not canonical text")
        val scalar = linkedMapOf<String, String>()
        val files = mutableListOf<WorkerPayloadFile>()
        var fileSection = false
        text.dropLast(1).split('\n').forEach { line ->
            if (line.startsWith("file=")) {
                fileSection = true
                if (files.size >= limits.files) invalid("payload file count exceeds limit")
                files += parseFile(line.removePrefix("file="))
            } else {
                if (fileSection) invalid("payload manifest properties follow file records")
                val separator = line.indexOf('=')
                if (separator <= 0) invalid("payload manifest contains a malformed property")
                val key = line.substring(0, separator)
                if (key !in SCALAR_KEYS || scalar.put(key, line.substring(separator + 1)) != null) {
                    invalid("payload manifest contains an unknown or duplicate property")
                }
            }
        }
        if (scalar.keys.toList() != SCALAR_KEYS) invalid("payload manifest properties are missing or noncanonical")
        if (scalar.getValue("format") != "1") invalid("unsupported payload manifest format")
        if (files != files.sortedBy(WorkerPayloadFile::path) || files.map(WorkerPayloadFile::path).distinct().size != files.size) {
            invalid("payload files are not uniquely and canonically ordered")
        }
        val total = files.fold(0L) { value, file -> Math.addExact(value, file.bytes) }
        if (total > limits.payloadBytes) invalid("payload bytes exceed limit")
        val payloadHash = decodeHash(scalar.getValue("payloadSha256"))
        val identity =
            WorkerIdentity(
                compilerVersion = scalar.getValue("compiler").also { if (it.isBlank()) invalid("compiler version is empty") },
                languageVersion = scalar.getValue("language").also { if (it.isBlank()) invalid("language version is empty") },
                codegenAbi = scalar.getValue("codegenAbi").toUIntOrNull() ?: invalid("invalid codegen ABI"),
                artifactWriterVersion =
                    scalar.getValue("artifactWriter").toUIntOrNull() ?: invalid("invalid artifact writer version"),
                payloadHash = payloadHash,
                standardLibraryAbi = Hash256.zero(),
            )
        val mainClass = scalar.getValue("mainClass")
        if (mainClass.isBlank() || mainClass.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            invalid("worker main class is invalid")
        }
        if (hashManifest(identity, mainClass, files) != payloadHash) invalid("payload manifest hash mismatch")
        return WorkerPayloadManifest(identity, mainClass, files, payloadHash)
    }

    private fun parseFile(record: String): WorkerPayloadFile {
        val fields = record.split('\t')
        if (fields.size != 3) invalid("payload manifest contains a malformed file record")
        try {
            validatePayloadPath(fields[0])
        } catch (exception: IllegalArgumentException) {
            throw WorkerPayloadException("payload path is invalid", exception)
        }
        val bytes = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: invalid("payload file size is invalid")
        return WorkerPayloadFile(fields[0], bytes, decodeHash(fields[2]))
    }

    private fun validatePayloadFile(
        root: Path,
        file: WorkerPayloadFile,
    ): Path {
        val path = root.resolve(file.path).normalize()
        if (!path.startsWith(root)) invalid("payload file escapes its root")
        requireNoSymbolicLinks(root, path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.bytes) {
            invalid("payload file is missing or has an invalid size: ${file.path}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (Hash256.of(digest.digest()) != file.sha256) invalid("payload file hash mismatch: ${file.path}")
        return path
    }

    private fun requireNoSymbolicLinks(
        root: Path,
        path: Path,
    ) {
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) invalid("payload contains a symbolic link")
        }
    }

    private fun requireRegularDirectory(root: Path) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            invalid("payload root is not a regular directory")
        }
    }

    private fun readBoundedRegularFile(
        path: Path,
        maximumBytes: Int,
    ): ByteArray {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            invalid("payload manifest is missing")
        }
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(minOf(maximumBytes.coerceAtLeast(1), DEFAULT_BUFFER_SIZE))
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                if (total > maximumBytes) invalid("payload manifest exceeds its byte limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun decodeManifest(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun decodeHash(value: String): Hash256 {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) invalid("invalid SHA-256 value")
        return Hash256.of(ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
    }

    private fun invalid(message: String): Nothing = throw WorkerPayloadException(message)

    private const val MANIFEST_FILE = "worker.payload"
    private val SCALAR_KEYS = listOf("format", "compiler", "language", "codegenAbi", "artifactWriter", "mainClass", "payloadSha256")
}
