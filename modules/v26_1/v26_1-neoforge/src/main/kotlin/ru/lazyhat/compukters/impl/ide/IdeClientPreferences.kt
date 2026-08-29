/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createDirectories

data class IdeLayoutSettings(
    val treeWidth: Int,
    val diagnosticsHeight: Int,
    val diagnosticsExpanded: Boolean,
) {
    init {
        require(treeWidth in MINIMUM_TREE_WIDTH..MAXIMUM_TREE_WIDTH) { "IDE tree width is outside its admitted range" }
        require(diagnosticsHeight in MINIMUM_DIAGNOSTICS_HEIGHT..MAXIMUM_DIAGNOSTICS_HEIGHT) {
            "IDE diagnostics height is outside its admitted range"
        }
    }

    companion object {
        const val MINIMUM_TREE_WIDTH = 64
        const val MAXIMUM_TREE_WIDTH = 4_096
        const val MINIMUM_DIAGNOSTICS_HEIGHT = 32
        const val MAXIMUM_DIAGNOSTICS_HEIGHT = 4_096
        const val DEFAULT_TREE_WIDTH = 240
        const val DEFAULT_DIAGNOSTICS_HEIGHT = 160

        fun defaults(): IdeLayoutSettings = admit(DEFAULT_TREE_WIDTH, DEFAULT_DIAGNOSTICS_HEIGHT, true)

        fun admit(
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
        ): IdeLayoutSettings =
            IdeLayoutSettings(
                treeWidth.coerceIn(MINIMUM_TREE_WIDTH, MAXIMUM_TREE_WIDTH),
                diagnosticsHeight.coerceIn(MINIMUM_DIAGNOSTICS_HEIGHT, MAXIMUM_DIAGNOSTICS_HEIGHT),
                diagnosticsExpanded,
            )
    }
}

internal interface IdeLayoutStore {
    fun load(): IdeLayoutSettings

    fun save(settings: IdeLayoutSettings)
}

internal class IdeClientPreferences(
    private val file: Path,
    private val layout: IdeLayoutStore,
) : IdePreferencesStore {
    init {
        require(file.isAbsolute && file.normalize() == file) { "IDE preference file must be absolute and normalized" }
    }

    override fun load(): IdePreferences? = runCatching(::loadChecked).getOrNull()

    override fun save(preferences: IdePreferences) {
        val parent = checkNotNull(file.parent) { "IDE preference file has no parent" }
        parent.createDirectories()
        check(!Files.isSymbolicLink(file)) { "IDE preference file must not be symbolic" }
        val bytes = encode(preferences).encodeToByteArray()
        check(bytes.size <= MAXIMUM_FILE_BYTES) { "IDE preference file exceeds byte limit" }
        val staging = parent.resolve(".${file.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(staging, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            forceFile(staging)
            move(staging, file)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    fun saveLayout(settings: IdeLayoutSettings) {
        layout.save(settings)
    }

    fun layout(): IdeLayoutSettings = layout.load()

    private fun loadChecked(): IdePreferences? {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(file) > MAXIMUM_FILE_BYTES) return null
        val bytes = Files.readAllBytes(file)
        val text = decodeStrict(bytes)
        val lines = text.takeIf { it.endsWith('\n') && '\r' !in it }?.dropLast(1)?.split('\n') ?: return null
        if (lines.size != 6 || lines[0] != "format=1") return null
        val project = decodeNullable(lines[1], "project") ?: return null
        val path = decodeNullable(lines[2], "file") ?: return null
        val caret = integer(lines[3], "caret") ?: return null
        val firstLine = integer(lines[4], "line") ?: return null
        val firstColumn = integer(lines[5], "column") ?: return null
        val currentLayout = layout.load()
        return IdePreferences.admit(
            project.takeUnless { it == NULL_VALUE },
            path.takeUnless { it == NULL_VALUE },
            caret,
            firstLine,
            firstColumn,
            currentLayout.treeWidth,
            currentLayout.diagnosticsHeight,
            currentLayout.diagnosticsExpanded,
        )
    }

    private fun encode(preferences: IdePreferences): String =
        buildString {
            appendLine("format=1")
            appendLine("project=${encodeNullable(preferences.lastProjectDirectory)}")
            appendLine("file=${encodeNullable(preferences.lastFile?.value)}")
            appendLine("caret=${preferences.caretUtf16}")
            appendLine("line=${preferences.firstVisibleLine}")
            appendLine("column=${preferences.firstVisibleColumn}")
        }

    private fun decodeNullable(
        line: String,
        name: String,
    ): String? {
        val prefix = "$name="
        if (!line.startsWith(prefix)) return null
        val value = line.removePrefix(prefix)
        if (value == NULL_VALUE) return NULL_VALUE
        return runCatching { decodeStrict(Base64.getUrlDecoder().decode(value)) }.getOrNull()
    }

    private fun integer(
        line: String,
        name: String,
    ): Int? {
        val prefix = "$name="
        if (!line.startsWith(prefix)) return null
        return line.removePrefix(prefix).toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun encodeNullable(value: String?): String =
        value?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) } ?: NULL_VALUE

    private fun decodeStrict(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun move(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun forceFile(path: Path) {
        try {
            java.nio.channels.FileChannel
                .open(path, StandardOpenOption.WRITE)
                .use { it.force(true) }
        } catch (_: IOException) {
            // The complete staged file can still be atomically replaced on filesystems without fsync support.
        }
    }

    companion object {
        const val MAXIMUM_FILE_BYTES = 16 * 1024
        private const val NULL_VALUE = "-"
    }
}
