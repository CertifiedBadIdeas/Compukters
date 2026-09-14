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

package ru.lazyhat.compukters.ide.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectManifestCodecTest {
    @Test
    fun `addons encode canonically in UTF-8 order`() {
        val manifest =
            ProjectManifestCodec.decode(
                """
                addons = ["mekanism", "create"]
                name = "hello \"world\""
                format = 3
                """.trimIndent(),
            )

        assertEquals(
            """
            format = 3
            name = "hello \"world\""

            addons = ["create", "mekanism"]
            """.trimIndent() + "\n",
            ProjectManifestCodec.encode(manifest),
        )
        assertEquals(manifest, ProjectManifestCodec.decode(ProjectManifestCodec.encode(manifest)))
    }

    @Test
    fun `empty addon list is valid and deterministic`() {
        val manifest = ProjectManifestCodec.decode("format = 3\nname = \"empty\"\n")

        assertEquals(emptySet(), manifest.addons)
        assertEquals("format = 3\nname = \"empty\"\n\naddons = []\n", ProjectManifestCodec.encode(manifest))
    }

    @Test
    fun `manifest rejects syntax duplicates unknown fields and unsupported formats`() {
        invalid("format = 3\nname = \"x\"\nname = \"y\"")
        invalid("format = 3\nname = \"x\"\nunknown = true")
        invalid("format = 2\nname = \"x\"")
        invalid("format = \"3\"\nname = \"x\"")
        invalid("name = \"x\"")
        invalid("format = 3")
        invalid("format = 3\nname = \"x\"\naddons = 1")
        invalid("format = 3\nname = \"x\"\naddons = [1]")
        invalid("format = 3\nname = \"x\"\naddons = [\"create\", \"create\"]")
        invalid("format = 3\nname = \"unterminated")
    }

    @Test
    fun `manifest rejects invalid project names`() {
        listOf("", ".", "..", "a/b", "a\\b", "bad\u0000name", "\ud800").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { ProjectManifest.of(name, emptySet()) }
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectManifest.of("a".repeat(65), emptySet(), ProjectLimits(projectNameCodePoints = 64))
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectManifest.of(
                "é".repeat(65),
                emptySet(),
                ProjectLimits(projectNameCodePoints = 128, projectNameUtf8Bytes = 128),
            )
        }
    }

    @Test
    fun `manifest rejects invalid addon identities`() {
        listOf("Create", "create.api", "create:kinetics", "a".repeat(65)).forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { AddonId(id) }
            invalid("format = 3\nname = \"x\"\naddons = [\"$id\"]")
        }
    }

    @Test
    fun `manifest enforces byte and addon-count limits`() {
        assertFailsWith<ManifestException> {
            ProjectManifestCodec.decode(
                "format = 3\nname = \"hello\"",
                ProjectLimits(manifestBytes = 8),
            )
        }
        assertFailsWith<ManifestException> {
            ProjectManifestCodec.decode(
                "format = 3\nname = \"hello\"\naddons = [\"one\", \"two\"]",
                ProjectLimits(addons = 1),
            )
        }
    }

    private fun invalid(source: String) {
        assertFailsWith<ManifestException>(source) { ProjectManifestCodec.decode(source) }
    }
}
