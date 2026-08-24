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

package ru.lazyhat.compukters.lang.runtime.vm

object VmArtifactVerifier {
    fun verify(artifact: ByteArray): Boolean = verify(artifact, VmRuntime.bridge())

    internal fun verify(
        artifact: ByteArray,
        bridge: LowLevelVmBridge,
    ): Boolean = bridge.verifyArtifact(artifact.copyOf())
}
