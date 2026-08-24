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
    fun packaged(): ByteArray = encodeShell(SystemProgramImage.shell())

    fun encodeShell(shellArtifact: ByteArray): ByteArray {
        require(shellArtifact.isNotEmpty()) { "system shell artifact must not be empty" }
        require(shellArtifact.size <= InstalledProgramStorage.MAXIMUM_ARTIFACT_BYTES) {
            "system shell artifact exceeds ${InstalledProgramStorage.MAXIMUM_ARTIFACT_BYTES} bytes"
        }
        val path = "/rom/shell".encodeToByteArray()
        val payloadSize = Math.addExact(HEADER_BYTES + ENTRY_FIXED_BYTES + path.size, shellArtifact.size)
        val payload =
            ByteBuffer
                .allocate(payloadSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(MAGIC)
                .putShort(VERSION)
                .putShort(0)
                .putInt(1)
                .putInt(path.size)
                .put(path)
                .put(FILE_KIND)
                .put(EXECUTABLE_FLAG)
                .putShort(0)
                .putLong(shellArtifact.size.toLong())
                .put(shellArtifact)
                .array()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private val MAGIC = "CPKTROM\u0000".encodeToByteArray()
    private const val VERSION: Short = 1
    private const val FILE_KIND: Byte = 2
    private const val EXECUTABLE_FLAG: Byte = 1
    private const val HEADER_BYTES = 16
    private const val ENTRY_FIXED_BYTES = 16
}
