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
import ru.lazyhat.compukters.ide.client.preferences.IdeProjectEditorState
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeClientPreferencesTest {
    @Test
    fun `computer associations survive byte budget pressure from old editor positions`() {
        val root = createTempDirectory("compukters-ide-preferences-budget-").toAbsolutePath().normalize()
        try {
            val store = IdeClientPreferences(root.resolve("session.preferences"), RecordingIdeLayoutStore(IdeLayoutSettings.defaults()))
            val id = java.util.UUID(0, 1)
            val states = linkedMapOf("active" to IdeProjectEditorState.admit("src/main.kt", 0, 0, 0))
            val longPath = List(8) { "x".repeat(255) }.joinToString("/")
            repeat(63) { states["old$it"] = IdeProjectEditorState.admit(longPath, 1, 2, 3) }
            store.save(IdePreferences.admit("active", states, 240, 160, true).rememberComputer(id, "active"))
            val restored = store.load()!!
            assertEquals(mapOf(id to "active"), restored.computerProjects)
            assertEquals("src/main.kt", restored.lastFile?.value)
            kotlin.test.assertTrue(restored.projectStates.size < states.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `computer projects persist across reopening with legacy migration and malformed rejection`() {
        val root = createTempDirectory("compukters-ide-computer-projects-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val layout = RecordingIdeLayoutStore(IdeLayoutSettings.defaults())
            val store = IdeClientPreferences(file, layout)
            val first = java.util.UUID(0, 1)
            val second = java.util.UUID(0, 2)
            val original =
                IdePreferences
                    .admit("demo", "src/main.kt", 3, 4, 5, 240, 160, true)
                    .rememberComputer(first, "demo")
                    .rememberComputer(second, "проект")
                    .rememberGitAuthor("Player", "p@example.invalid")
            store.save(original)
            val restored = IdeClientPreferences(file, layout).load()!!
            assertEquals(original.computerProjects, restored.computerProjects)
            assertEquals("src/main.kt", restored.lastFile?.value)
            assertEquals("Player", restored.gitAuthorName)
            val valid =
                java.nio.file.Files
                    .readString(file)
            val computerLine = valid.lines().first { it.startsWith("computer=") }
            for (invalid in listOf(
                valid + "$computerLine\n",
                valid.replace(first.toString(), "1-1-1-1-1"),
                valid.replace(computerLine, "computer=$first|Li4vZXNjYXBl"),
            )) {
                file.writeText(invalid)
                assertNull(store.load())
            }
            val legacy =
                valid
                    .replace("format=4", "format=3")
                    .lines()
                    .filterNot { it.startsWith("computer=") }
                    .joinToString("\n")
            file.writeText(legacy)
            val migrated = store.load()!!
            assertEquals(emptyMap(), migrated.computerProjects)
            assertEquals("demo", migrated.lastProjectDirectory)
            assertEquals("Player", migrated.gitAuthorName)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scale selection survives reopening and independent layout and session saves`() {
        val root = createTempDirectory("compukters-ide-scale-").toAbsolutePath().normalize()
        try {
            val layout = RecordingIdeLayoutStore(IdeLayoutSettings.defaults())
            val file = root.resolve("session.preferences")
            val store = IdeClientPreferences(file, layout)
            assertEquals(IdeUiScale.AUTO, store.uiScale())
            for (mode in IdeUiScale.entries) {
                store.saveUiScale(mode)
                store.saveLayout(IdeLayoutSettings.admit(233, 151, false))
                store.save(IdePreferences.admit("demo", "src/main.kt", 12, 4, 5, 233, 151, false))
                assertEquals(mode, IdeClientPreferences(file, layout).uiScale())
                assertEquals(233, store.layout().treeWidth)
                assertEquals("demo", store.load()!!.lastProjectDirectory)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `commit author persists across stores and can be replaced while format two remains readable`() {
        val root = createTempDirectory("compukters-ide-author-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val layout = RecordingIdeLayoutStore(IdeLayoutSettings.defaults())
            val store = IdeClientPreferences(file, layout)
            val original = IdePreferences.admit("demo", "src/main.kt", 12, 4, 5, 240, 160, true)
            store.save(original.rememberGitAuthor("Автор😀", "player@example.invalid"))
            val reopened = IdeClientPreferences(file, layout)
            val restored = reopened.load()!!
            assertEquals("Автор😀", restored.gitAuthorName)
            assertEquals("player@example.invalid", restored.gitAuthorEmail)
            assertEquals("src/main.kt", restored.lastFile?.value)
            reopened.save(restored.rememberGitAuthor("Other", "other@example.invalid"))
            assertEquals("Other", store.load()!!.gitAuthorName)
            assertEquals("other@example.invalid", store.load()!!.gitAuthorEmail)

            file.writeText("format=2\nactive=ZGVtbw\nstate=ZGVtbw|c3JjL21haW4ua3Q|12|4|5\n")
            val migrated = store.load()!!
            assertEquals("demo", migrated.lastProjectDirectory)
            assertEquals("src/main.kt", migrated.lastFile?.value)
            assertEquals("", migrated.gitAuthorName)
            assertEquals("", migrated.gitAuthorEmail)
            store.save(migrated.rememberGitAuthor("Player", "player@example.invalid"))
            assertEquals("Player", reopened.load()!!.gitAuthorName)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session state round trips separately from persistent layout`() {
        val root = createTempDirectory("compukters-ide-preferences-").toAbsolutePath().normalize()
        try {
            val layout = RecordingIdeLayoutStore(IdeLayoutSettings.admit(180, 120, true))
            val preferences = IdeClientPreferences(root.resolve("session.preferences"), layout)
            preferences.save(IdePreferences.admit("проект", "src/главная.kt", 12, 4, 5, 999, 888, false))

            assertEquals(0, layout.saves.size, "ordinary session saves must not rewrite NeoForge layout config")
            val restored = preferences.load()!!
            assertEquals("проект", restored.lastProjectDirectory)
            assertEquals("src/главная.kt", restored.lastFile?.value)
            assertEquals(12, restored.caretUtf16)
            assertEquals(4, restored.firstVisibleLine)
            assertEquals(5, restored.firstVisibleColumn)
            assertEquals(180, restored.treeWidth)
            assertEquals(120, restored.diagnosticsHeight)
            assertEquals(true, restored.diagnosticsExpanded)

            preferences.saveLayout(IdeLayoutSettings.admit(233, 151, false))
            assertEquals(listOf(IdeLayoutSettings.admit(233, 151, false)), layout.saves)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple project states round trip and format one migrates`() {
        val root = createTempDirectory("compukters-ide-preferences-projects-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val preferences = IdeClientPreferences(file, RecordingIdeLayoutStore(IdeLayoutSettings.defaults()))
            preferences.save(
                IdePreferences.admit(
                    "second",
                    linkedMapOf(
                        "first" to IdeProjectEditorState.admit("src/first.kt", 11, 2, 3),
                        "second" to IdeProjectEditorState.admit("src/second.kt", 22, 4, 5),
                    ),
                    240,
                    160,
                    true,
                ),
            )

            val restored = preferences.load()!!
            assertEquals("second", restored.lastProjectDirectory)
            assertEquals("src/first.kt", restored.projectState("first")?.file?.value)
            assertEquals(11, restored.projectState("first")?.caretUtf16)
            assertEquals("src/second.kt", restored.projectState("second")?.file?.value)

            val encodedProject = Base64.getUrlEncoder().withoutPadding().encodeToString("legacy".encodeToByteArray())
            val encodedFile = Base64.getUrlEncoder().withoutPadding().encodeToString("src/main.kt".encodeToByteArray())
            file.writeText("format=1\nproject=$encodedProject\nfile=$encodedFile\ncaret=12\nline=4\ncolumn=5\n")

            val migrated = preferences.load()!!
            assertEquals("legacy", migrated.lastProjectDirectory)
            assertEquals("src/main.kt", migrated.projectState("legacy")?.file?.value)
            assertEquals(12, migrated.projectState("legacy")?.caretUtf16)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing malformed and oversized session files recover without state`() {
        val root = createTempDirectory("compukters-ide-preferences-invalid-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val preferences = IdeClientPreferences(file, RecordingIdeLayoutStore(IdeLayoutSettings.defaults()))
            assertNull(preferences.load())

            file.writeText("format=wrong\n")
            assertNull(preferences.load())

            file.writeBytes(ByteArray(IdeClientPreferences.MAXIMUM_FILE_BYTES + 1))
            assertNull(preferences.load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private class RecordingIdeLayoutStore(
    private var current: IdeLayoutSettings,
) : IdeLayoutStore {
    private var scale = IdeUiScale.AUTO

    override fun loadUiScale(): IdeUiScale = scale

    override fun saveUiScale(scale: IdeUiScale) {
        this.scale = scale
    }

    val saves = mutableListOf<IdeLayoutSettings>()

    override fun load(): IdeLayoutSettings = current

    override fun save(settings: IdeLayoutSettings) {
        current = settings
        saves += settings
    }
}
