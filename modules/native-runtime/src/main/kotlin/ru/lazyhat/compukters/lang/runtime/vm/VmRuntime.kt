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

import java.nio.file.Path

object VmRuntime {
    private val loader = NativeRuntimeLoader.production(VmRuntime::class.java)

    fun ensureLoaded(): VmRuntimeLoadResult = loader.ensurePackagedLoaded()

    fun requireLoaded(): VmRuntimeLoadResult.Loaded = requireSuccess(ensureLoaded())

    fun loadNativeLibrary(library: Path) {
        requireSuccess(loader.ensureExplicitLoaded(library))
    }

    private fun requireSuccess(result: VmRuntimeLoadResult): VmRuntimeLoadResult.Loaded =
        when (result) {
            is VmRuntimeLoadResult.Loaded -> result
            is VmRuntimeLoadResult.Failed -> throw VmRuntimeLoadException(result.failure)
        }
}
