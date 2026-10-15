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

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

/** Checks the challenged URI too, rather than trusting only the configured origin URL. */
internal class ScopedGitCredentialsProvider(
    credentials: GitCredentials,
) : CredentialsProvider(),
    AutoCloseable {
    val scope = credentials.scope
    private val username = credentials.username
    private val password = credentials.token()
    private var closed = false

    override fun isInteractive() = false

    override fun supports(vararg items: CredentialItem): Boolean =
        items.all {
            it is CredentialItem.Username || it is CredentialItem.Password || it is CredentialItem.InformationalMessage
        }

    @Synchronized
    override fun get(
        uri: URIish,
        vararg items: CredentialItem,
    ): Boolean {
        if (closed || !scope.matches(uri.toString()) || !supports(*items)) return false
        for (item in items) {
            when (item) {
                is CredentialItem.Username -> item.value = username
                is CredentialItem.Password -> item.value = password
            }
        }
        return true
    }

    @Synchronized
    override fun close() {
        closed = true
        password.fill('\u0000')
    }

    override fun toString() = "ScopedGitCredentialsProvider(<redacted>)"
}
