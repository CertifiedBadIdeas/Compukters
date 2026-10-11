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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFileSearchTest {
    private fun entries(vararg paths: String) =
        paths.map {
            ProjectTreeEntry(ProjectPath.file(it), if (it.endsWith(".bin")) ProjectFileKind.Binary(4) else ProjectFileKind.Text(4), null)
        }

    private fun search(query: String) =
        ProjectFileSearch
            .search(
                entries("test/main.kt", "src/main.kt", "main.kt/readme.txt", "src/main.kt.bak", "src/domain.kt", "data.bin"),
                query,
            ).map {
                it.value
            }

    @Test
    fun `filenames rank ahead of path matches and duplicate names retain full paths`() {
        assertEquals(listOf("src/main.kt", "test/main.kt", "src/main.kt.bak", "src/domain.kt", "main.kt/readme.txt"), search("MAIN.KT"))
        assertEquals(listOf("src/main.kt", "src/main.kt.bak"), search("src\\main.kt"))
        assertEquals(listOf("src/domain.kt", "src/main.kt", "src/main.kt.bak"), search("src .kt"))
        assertEquals(emptyList(), search("missing"))
    }

    @Test
    fun `empty query promotes admitted open files excludes directories and bounds results`() {
        val files = entries("b.kt", "a.kt", "data.bin") + ProjectTreeEntry(ProjectPath.file("src"), ProjectFileKind.Directory, null)
        assertEquals(
            listOf(
                "data.bin",
                "b.kt",
            ),
            ProjectFileSearch
                .search(
                    files,
                    " ",
                    listOf(ProjectPath.file("gone.kt"), ProjectPath.file("data.bin"), ProjectPath.file("b.kt")),
                    2,
                ).map {
                    it.value
                },
        )
        assertEquals(listOf("a.kt", "b.kt", "data.bin"), ProjectFileSearch.search(files, "").map { it.value })
    }
}
