/*
 * The Compukters Developers
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
