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

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import java.net.Proxy
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCredentialSecurityTest {
    @Test
    fun `credential scope compares exact HTTPS origins with normalized default port`() {
        val scope = GitCredentialScope.fromRemote("https://GitHub.COM/org/repo")
        for (url in listOf("https://github.com/other/repo", "https://GITHUB.com:443/org/repo")) assertTrue(scope.matches(url))
        for (url in listOf(
            "https://github.com:8443/repo",
            "http://github.com/repo",
            "https://github.com.evil.invalid/repo",
            "https://evil.invalid/repo",
            "https://github.com./repo",
            "https://user:secret@github.com/repo",
        )) {
            assertFalse(scope.matches(url), url)
        }
        assertEquals("github.com", scope.authority)
        assertEquals("git.example.org:8443", GitCredentialScope.fromRemote("https://git.example.org:8443").authority)
    }

    @Test
    fun `provider refuses other origins without filling credentials and clears its operation copy`() {
        GitCredentials("player", "test-secret".toCharArray()).use { credentials ->
            val provider = ScopedGitCredentialsProvider(credentials)
            assertFalse(provider.toString().contains("test-secret"))
            for (url in listOf("https://other.invalid/repo", "https://github.com:8443/repo", "http://github.com/repo")) {
                val user = CredentialItem.Username()
                val password = CredentialItem.Password()
                assertFalse(provider.get(URIish(url), user, password))
                assertEquals(null, user.value)
                assertEquals(null, password.value)
            }
            val user = CredentialItem.Username()
            val password = CredentialItem.Password()
            assertTrue(provider.get(URIish("https://github.com/repo"), user, password))
            assertEquals("player", user.value)
            assertEquals("test-secret", String(password.value))
            password.clear()
            provider.close()
            assertFalse(provider.get(URIish("https://github.com/repo"), CredentialItem.Password()))
            assertEquals("test-secret", String(credentials.token()))
        }
    }

    @Test
    fun `every HTTP connection rejects redirected host port scheme and credentials before delegate access`() {
        val opened = mutableListOf<URL>()
        val delegate = JDKHttpConnectionFactory()
        val factory =
            ScopedGitHttpConnectionFactory(
                GitCredentialScope.GITHUB,
                object : HttpConnectionFactory {
                    override fun create(url: URL): HttpConnection {
                        opened += url
                        return delegate.create(url)
                    }

                    override fun create(
                        url: URL,
                        proxy: Proxy,
                    ): HttpConnection {
                        opened += url
                        return delegate.create(url, proxy)
                    }
                },
            )
        for (url in listOf(
            "https://evil.invalid/repo",
            "https://github.com:8443/repo",
            "http://github.com/repo",
            "https://user:secret@github.com/repo",
        )) {
            assertFailsWith<GitTransportSecurityException> { factory.create(java.net.URI(url).toURL()) }
            assertFailsWith<GitTransportSecurityException> { factory.create(java.net.URI(url).toURL(), Proxy.NO_PROXY) }
        }
        assertTrue(opened.isEmpty())
        val connection = factory.create(java.net.URI("https://github.com/repo").toURL())
        assertEquals(1, opened.size)
        val session = factory.newSession()
        assertFailsWith<GitTransportSecurityException> { session.configure(connection, false) }
        assertEquals(connection, session.configure(connection, true))
        session.close()
    }

    @Test
    fun `remote error text and cause chains cannot disclose reflected credentials`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                gitNetworkOperation<Unit> {
                    throw java.io.IOException("server echoed test-secret", RuntimeException("Authorization: test-secret"))
                }
            }
        assertFalse(failure.stackTraceToString().contains("test-secret"))
        assertEquals(null, failure.cause)
    }

    @Test
    fun `Git commands reject foreign clone fetch pull pushURL and actual URL rewrites`() {
        val project = ProjectCatalog.open(createTempDirectory("compukters-git-scope-")).create("demo")
        val backend = JGitBackend()
        backend.execute(project.handle, GitOperation.Init, null, GitCancellation())
        Git.open(project.handle.canonicalPath.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git
                .commit()
                .setMessage("Initial")
                .setAuthor("Test", "test@example.invalid")
                .call()
            val config = git.repository.config
            config.setString("remote", "origin", "url", "https://github.com/org/repo")
            config.setString("remote", "origin", "pushurl", "https://wrong.invalid/repo")
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.setString("branch", "main", "remote", "origin")
            config.setString("branch", "main", "merge", "refs/heads/main")
            config.save()
            GitCredentials("player", "test-secret".toCharArray()).use { credentials ->
                fun blocked(action: () -> Unit) {
                    val failure = assertFailsWith<IllegalStateException>(block = action)
                    assertTrue(failure.message.orEmpty().contains("authenticate separately"), failure.message)
                    assertFalse(failure.toString().contains("test-secret"))
                    assertEquals(null, failure.cause)
                }
                blocked { backend.execute(project.handle, GitOperation.Push, credentials, GitCancellation()) }
                config.setString("remote", "origin", "url", "https://wrong.invalid/repo")
                config.save()
                for (operation in listOf(GitOperation.Fetch, GitOperation.Pull)) {
                    blocked { backend.execute(project.handle, operation, credentials, GitCancellation()) }
                }
                blocked {
                    backend.clone(
                        "https://wrong.invalid/repo",
                        createTempDirectory("compukters-clone-scope-"),
                        credentials,
                        GitCancellation(),
                    )
                }
                config.setString("remote", "origin", "url", "https://github.com/org/repo")
                config.setString("url", "https://wrong.invalid/", "insteadOf", "https://github.com/")
                config.save()
                blocked { backend.execute(project.handle, GitOperation.Fetch, credentials, GitCancellation()) }
            }
        }
    }
}
