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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaLicenseInventoryTest {
    @Test
    fun parsesCompleteInventory() {
        val record = MediaLicenseInventory.parse(VALID).single()

        assertEquals("texture.png", record.path)
        assertEquals(MediaAssetCategory.ORIGINAL, record.category)
        assertTrue(record.packaged)
    }

    @Test
    fun rejectsDuplicatePaths() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                MediaLicenseInventory.parse(VALID + VALID.substringAfter('\n'))
            }

        assertTrue(error.message.orEmpty().contains("duplicate media inventory path"))
    }

    @Test
    fun rejectsMalformedRecordsAndUnsafePaths() {
        listOf(
            VALID.replace("\ttrue\n", "\tmaybe\n"),
            VALID.replace("original", "unknown"),
            VALID.replace("texture.png", "../texture.png"),
            VALID.replace("texture.png", "/texture.png"),
            VALID.replace("Compukters", ""),
            VALID.replace("\ttrue", ""),
        ).forEach { inventory ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaLicenseInventory.parse(inventory)
            }
        }
    }

    @Test
    fun rejectsUnclassifiedDiscoveredMedia() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                MediaLicenseInventory.verify(
                    records = MediaLicenseInventory.parse(VALID),
                    discoveredPaths = setOf("texture.png", "sound.ogg"),
                    existingPaths = setOf("texture.png", "sound.ogg", "licenses/texture.md"),
                )
            }

        assertTrue(error.message.orEmpty().contains("sound.ogg"))
    }

    @Test
    fun rejectsStaleInventoryRecord() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                MediaLicenseInventory.verify(
                    records = MediaLicenseInventory.parse(VALID),
                    discoveredPaths = emptySet(),
                    existingPaths = setOf("texture.png", "licenses/texture.md"),
                )
            }

        assertTrue(error.message.orEmpty().contains("texture.png"))
    }

    @Test
    fun rejectsMissingAssetOrProvenancePath() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                MediaLicenseInventory.verify(
                    records = MediaLicenseInventory.parse(VALID),
                    discoveredPaths = setOf("texture.png"),
                    existingPaths = setOf("texture.png"),
                )
            }

        assertTrue(error.message.orEmpty().contains("licenses/texture.md"))
    }

    private companion object {
        val VALID =
            "path\tcategory\torigin\tcopyright\tlicense\tprovenance\tpackaged\n" +
                "texture.png\toriginal\tCompukters\tVsevolod Petrov (lazyhat)\tCC-BY-4.0\tlicenses/texture.md\ttrue\n"
    }
}
