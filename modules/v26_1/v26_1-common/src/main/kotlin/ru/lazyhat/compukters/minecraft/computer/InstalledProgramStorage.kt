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

import com.mojang.serialization.Codec
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.nio.ByteBuffer

class InstalledProgramStorage(
    private val maximumArtifactBytes: Int = MAXIMUM_ARTIFACT_BYTES,
) {
    init {
        require(maximumArtifactBytes > 0) { "maximum artifact bytes must be positive" }
    }

    private var installedArtifact: ByteArray? = null

    fun hasArtifact(): Boolean = installedArtifact != null

    fun artifact(): ByteArray? = installedArtifact?.copyOf()

    fun install(artifact: ByteArray) {
        require(artifact.isNotEmpty()) { "artifact must not be empty" }
        require(artifact.size <= maximumArtifactBytes) {
            "artifact exceeds $maximumArtifactBytes bytes"
        }
        installedArtifact = artifact.copyOf()
    }

    fun clear(): Boolean {
        if (installedArtifact == null) return false
        installedArtifact = null
        return true
    }

    fun save(root: ValueOutput) {
        val artifact = installedArtifact
        if (artifact == null) {
            root.discard(ROOT_KEY)
            return
        }
        savePayload(root.child(ROOT_KEY))
    }

    internal fun savePayload(payload: ValueOutput) {
        val artifact = installedArtifact
        payload.putInt(SCHEMA_KEY, CURRENT_SCHEMA)
        if (artifact == null) {
            payload.discard(ARTIFACT_KEY)
        } else {
            payload.store(ARTIFACT_KEY, Codec.BYTE_BUFFER, ByteBuffer.wrap(artifact.copyOf()))
        }
    }

    fun load(root: ValueInput) {
        loadPayload(root.child(ROOT_KEY).orElse(null))
    }

    internal fun loadPayload(payload: ValueInput?) {
        installedArtifact = null
        payload ?: return
        if (payload.getIntOr(SCHEMA_KEY, 0) != CURRENT_SCHEMA) return
        val buffer = payload.read(ARTIFACT_KEY, Codec.BYTE_BUFFER).orElse(null) ?: return
        val artifact = ByteArray(buffer.remaining())
        buffer.slice().get(artifact)
        if (artifact.isEmpty() || artifact.size > maximumArtifactBytes) return
        installedArtifact = artifact
    }

    companion object {
        const val MAXIMUM_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
        private const val CURRENT_SCHEMA = 1
        internal const val ROOT_KEY = "compukters"
        private const val SCHEMA_KEY = "schema"
        private const val ARTIFACT_KEY = "artifact"
    }
}
