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
import org.junit.jupiter.api.Test

class BuildVersionSupportTest {
    @Test
    fun keepsReleaseVersionWhenHeadHasMatchingVersionTag() {
        assertEquals(
            "0.2.0",
            computeEffectiveBuildVersion(
                baseVersion = "0.2.0",
                headTags = listOf("v0.2.0", "other"),
                shortHash = "abc1234",
            ),
        )
    }

    @Test
    fun keepsReleaseVersionWhenHeadHasMatchingBareVersionTag() {
        assertEquals(
            "0.2.0",
            computeEffectiveBuildVersion(
                baseVersion = "0.2.0",
                headTags = listOf("0.2.0"),
                shortHash = "abc1234",
            ),
        )
    }

    @Test
    fun marksUntaggedHeadAsShortSnapshot() {
        assertEquals(
            "0.2.0-S-abc1234",
            computeEffectiveBuildVersion(
                baseVersion = "0.2.0",
                headTags = emptyList(),
                shortHash = "abc1234",
            ),
        )
    }

    @Test
    fun marksMismatchedTagAsShortSnapshot() {
        assertEquals(
            "0.2.0-S-abc1234",
            computeEffectiveBuildVersion(
                baseVersion = "0.2.0",
                headTags = listOf("v0.1.0"),
                shortHash = "abc1234",
            ),
        )
    }
}
