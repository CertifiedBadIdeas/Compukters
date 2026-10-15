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

import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import java.io.IOException
import java.net.Proxy
import java.net.URL

internal class GitTransportSecurityException(
    message: String,
) : IOException(message)

/** Guards every connection before JGit can attach cached Authorization headers, including redirects. */
internal class ScopedGitHttpConnectionFactory(
    private val scope: GitCredentialScope,
    private val delegate: HttpConnectionFactory,
) : HttpConnectionFactory2 {
    private fun check(url: URL) {
        if (!scope.matches(url.toExternalForm())) {
            throw GitTransportSecurityException("Git server changed · authenticate separately for the destination server")
        }
    }

    override fun create(url: URL): HttpConnection {
        check(url)
        return delegate.create(url)
    }

    override fun create(
        url: URL,
        proxy: Proxy,
    ): HttpConnection {
        check(url)
        return delegate.create(url, proxy)
    }

    override fun newSession(): HttpConnectionFactory2.GitSession {
        val session = (delegate as? HttpConnectionFactory2)?.newSession()
        return object : HttpConnectionFactory2.GitSession {
            override fun configure(
                connection: HttpConnection,
                sslVerify: Boolean,
            ): HttpConnection {
                if (!sslVerify) throw GitTransportSecurityException("Git HTTPS certificate verification must remain enabled")
                return session?.configure(connection, true) ?: connection
            }

            override fun close() {
                session?.close()
            }
        }
    }
}

internal fun <T> gitNetworkOperation(action: () -> T): T =
    try {
        action()
    } catch (failure: Exception) {
        val blocked =
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<GitTransportSecurityException>()
                .firstOrNull()
        // Remote error text can echo credentials. Keep it out of IDE diagnostics and exception chains.
        throw IllegalStateException(blocked?.message ?: "Git network operation failed · check server, credentials and connection")
    }
