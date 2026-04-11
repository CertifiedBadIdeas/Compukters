/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.core.computer.vm

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ComputerWorkspaceInitializer(
    private val rootPath: Path,
) {
    fun ensureInitialized(computerId: Int): Path {
        val root = rootPath.resolve(computerId.toString()).normalize()
        if (root.exists()) return root
        root.createDirectories()
        cloneRomTo(root)
        return root
    }

    private fun cloneRomTo(targetDir: Path) {
        val classLoader = ComputerWorkspaceInitializer::class.java.classLoader
        val romIndex =
            classLoader
                .getResourceAsStream("rom/rom.index")
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                ?: return

        for (fileName in romIndex.lines()) {
            val trimmed = fileName.trim()
            if (trimmed.isEmpty()) continue
            val content =
                classLoader
                    .getResourceAsStream("rom/$trimmed")
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    ?: continue
            val target = targetDir.resolve(trimmed).normalize()
            if (!target.startsWith(targetDir)) continue
            target.parent?.createDirectories()
            target.writeText(content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
    }
}
