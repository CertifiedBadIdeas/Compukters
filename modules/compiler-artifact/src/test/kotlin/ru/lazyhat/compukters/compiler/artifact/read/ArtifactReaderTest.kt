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

package ru.lazyhat.compukters.compiler.artifact.read

import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.languageRuntimeArtifact
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArtifactReaderTest {
    @Test
    fun `writer reader writer round trip preserves canonical bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val decoded = ArtifactReader.read(encoded)
        val repeatedResult = ArtifactWriter.write(decoded)
        val repeated = assertIs<ArtifactWriteResult.Success>(repeatedResult, repeatedResult.toString()).bytes

        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `reader rejects corruption and trailing bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val corrupted = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(corrupted) }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(encoded + 0) }
    }
}
