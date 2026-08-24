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

package ru.lazyhat.compukters.minecraft.computer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object SystemRomImage {
    fun packaged(): ByteArray = encodePrograms(SystemProgramImage.boot(), SystemProgramImage.shell())

    fun encodePrograms(
        bootArtifact: ByteArray,
        shellArtifact: ByteArray,
    ): ByteArray {
        val programs = listOf("/rom/boot" to bootArtifact, "/rom/shell" to shellArtifact).sortedBy { it.first }
        programs.forEach { (path, artifact) ->
            require(artifact.isNotEmpty()) { "system program $path must not be empty" }
            require(artifact.size <= MAXIMUM_PROGRAM_BYTES) {
                "system program $path exceeds $MAXIMUM_PROGRAM_BYTES bytes"
            }
        }
        val payloadSize =
            programs.fold(HEADER_BYTES) { size, (path, artifact) ->
                Math.addExact(size, ENTRY_FIXED_BYTES + path.encodeToByteArray().size + artifact.size)
            }
        val buffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer
            .put(MAGIC)
            .putShort(VERSION)
            .putShort(0)
            .putInt(programs.size)
        programs.forEach { (pathText, artifact) ->
            val path = pathText.encodeToByteArray()
            buffer
                .putInt(path.size)
                .put(path)
                .put(FILE_KIND)
                .put(EXECUTABLE_FLAG)
                .putShort(0)
                .putLong(artifact.size.toLong())
                .put(artifact)
        }
        val payload = buffer.array()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private val MAGIC = "CPKTROM\u0000".encodeToByteArray()
    private const val VERSION: Short = 1
    private const val FILE_KIND: Byte = 2
    private const val EXECUTABLE_FLAG: Byte = 1
    private const val HEADER_BYTES = 16
    private const val ENTRY_FIXED_BYTES = 16
    private const val MAXIMUM_PROGRAM_BYTES = 16 * 1024 * 1024
}
