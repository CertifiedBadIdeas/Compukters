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

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

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

    fun save(root: CompoundTag) {
        val artifact = installedArtifact
        if (artifact == null) {
            root.remove(ROOT_KEY)
            return
        }
        val payload = CompoundTag()
        payload.putInt(SCHEMA_KEY, CURRENT_SCHEMA)
        payload.putByteArray(ARTIFACT_KEY, artifact)
        root.put(ROOT_KEY, payload)
    }

    fun load(root: CompoundTag) {
        installedArtifact = null
        if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND.toInt())) return
        val payload = root.getCompound(ROOT_KEY)
        if (!payload.contains(SCHEMA_KEY, Tag.TAG_INT.toInt()) || payload.getInt(SCHEMA_KEY) != CURRENT_SCHEMA) return
        if (!payload.contains(ARTIFACT_KEY, Tag.TAG_BYTE_ARRAY.toInt())) return
        val artifact = payload.getByteArray(ARTIFACT_KEY)
        if (artifact.isEmpty() || artifact.size > maximumArtifactBytes) return
        installedArtifact = artifact.copyOf()
    }

    companion object {
        const val MAXIMUM_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
        private const val CURRENT_SCHEMA = 1
        private const val ROOT_KEY = "compukters"
        private const val SCHEMA_KEY = "schema"
        private const val ARTIFACT_KEY = "artifact"
    }
}
