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

import java.net.HttpURLConnection
import java.net.URI

/** Validates the token itself through GitHub; never follows redirects or queries repositories. */
internal class GitHubCredentialValidator(
    private val request: (String, String, GitCancellation) -> Reply = ::httpRequest,
) {
    data class Reply(
        val status: Int,
    )

    fun validate(
        credentials: GitCredentials,
        cancellation: GitCancellation,
    ): GitCredentialCheck {
        val token = credentials.token()
        try {
            if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) {
                return GitCredentialCheck(false, "Token must not contain whitespace")
            }
            if (!credentials.scope.matches("https://github.com")) {
                return GitCredentialCheck(false, "Token check unavailable for this server · save without check", canSave = true)
            }
            cancellation.check()
            val identity = request("https://api.github.com/user", "Bearer ${String(token)}", cancellation)
            when (identity.status) {
                401 -> return GitCredentialCheck(false, "Invalid or expired GitHub token")
                403, 429 -> return GitCredentialCheck(false, "GitHub refused the check · policy or rate limit")
                200 -> Unit
                else -> return GitCredentialCheck(false, "GitHub token check failed (HTTP ${identity.status})")
            }
            return GitCredentialCheck(true, "Token is valid")
        } catch (_: java.io.IOException) {
            return GitCredentialCheck(false, "Cannot reach GitHub · check connection and retry")
        } finally {
            token.fill('\u0000')
        }
    }

    companion object {
        private fun httpRequest(
            url: String,
            authorization: String,
            cancellation: GitCancellation,
        ): Reply {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Authorization", authorization)
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Compukters-IDE")
                val status = connection.responseCode
                cancellation.check()
                return Reply(status)
            } finally {
                connection.disconnect()
            }
        }
    }
}
