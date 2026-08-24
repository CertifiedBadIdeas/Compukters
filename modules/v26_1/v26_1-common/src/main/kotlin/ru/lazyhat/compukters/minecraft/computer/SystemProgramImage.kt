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
    fun boot(): ByteArray = load(BOOT_RESOURCE, "boot")

    fun shell(): ByteArray = load(SHELL_RESOURCE, "shell")

    private fun load(resource: String, name: String): ByteArray =
        checkNotNull(SystemProgramImage::class.java.getResourceAsStream(resource)) {
            "packaged system $name is missing: $resource"
        }.use { it.readAllBytes() }

    private const val BOOT_RESOURCE = "/system/programs/boot"
    private const val SHELL_RESOURCE = "/system/programs/shell"
}
