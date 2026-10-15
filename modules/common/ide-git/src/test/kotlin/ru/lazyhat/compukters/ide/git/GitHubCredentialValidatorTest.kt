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

package ru.lazyhat.compukters.ide.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubCredentialValidatorTest {
    @Test
    fun `validity checks only the authenticated user endpoint without repository permissions`() {
        val calls = mutableListOf<String>()
        val validator =
            GitHubCredentialValidator { url, _, _ ->
                calls += url
                GitHubCredentialValidator.Reply(200)
            }
        GitCredentials("player", "github_pat_example".toCharArray()).use { credentials ->
            val result = validator.validate(credentials, GitCancellation())
            assertTrue(result.valid)
            assertTrue(result.canSave)
            assertEquals("Token is valid", result.message)
        }
        assertEquals(listOf("https://api.github.com/user"), calls)
    }

    @Test
    fun `invalid tokens cannot be saved and policy failures are distinguished`() {
        GitCredentials("player", "example".toCharArray()).use { credentials ->
            for (status in listOf(401, 403, 429, 500, 302)) {
                val result =
                    GitHubCredentialValidator { _, _, _ -> GitHubCredentialValidator.Reply(status) }
                        .validate(credentials, GitCancellation())
                assertFalse(result.valid)
                assertFalse(result.canSave)
                assertFalse(result.message.contains("example"))
            }
        }
    }

    @Test
    fun `other hosts do not receive GitHub checks and network failures do not imply invalid tokens`() {
        val validator = GitHubCredentialValidator { _, _, _ -> throw java.io.IOException("secret server text") }
        GitCredentials("player", "example".toCharArray()).use { credentials ->
            val unsupported =
                GitCredentials("player", "example".toCharArray(), GitCredentialScope.fromRemote("https://git.example.org")).use {
                    validator.validate(it, GitCancellation())
                }
            assertFalse(unsupported.valid)
            assertTrue(unsupported.canSave)
            val offline = validator.validate(credentials, GitCancellation())
            assertFalse(offline.valid)
            assertFalse(offline.canSave)
            assertFalse(offline.message.contains("secret"))
        }
    }
}
