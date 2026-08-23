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

internal object SystemProgramImage {
    fun shell(): ByteArray =
        checkNotNull(SystemProgramImage::class.java.getResourceAsStream(SHELL_RESOURCE)) {
            "packaged system shell is missing: $SHELL_RESOURCE"
        }.use { it.readAllBytes() }

    private const val SHELL_RESOURCE = "/system/programs/shell.cpkt"
}
