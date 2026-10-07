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

import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Git operates on client-local roots; this contract is independent of its implementation. */
interface GitBackend {
    fun clone(
        remote: String,
        destination: Path,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    )

    fun execute(
        project: ProjectHandle,
        operation: GitOperation,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    ): GitResult
}

class GitCredentials(
    val username: String,
    token: CharArray,
) : AutoCloseable {
    private val password = token.copyOf()

    fun token(): CharArray = password.copyOf()

    override fun close() {
        password.fill('\u0000')
    }

    override fun toString(): String = "GitCredentials(<redacted>)"
}

class GitCancellation {
    private val cancelled = AtomicBoolean()

    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean get() = cancelled.get() || Thread.currentThread().isInterrupted

    fun check() {
        check(!isCancelled) { "Git operation cancelled" }
    }
}

data class GitLimits(
    val files: Int = 4096,
    val history: Int = 100,
    val diffBytes: Int = 256 * 1024,
    val repositoryBytes: Long = 512L * 1024 * 1024,
    val timeoutSeconds: Int = 60,
) {
    init {
        require(files > 0 && history > 0 && diffBytes > 0 && repositoryBytes > 0 && timeoutSeconds > 0)
    }
}

sealed interface GitOperation {
    data object Init : GitOperation

    data object Status : GitOperation

    data object History : GitOperation

    data class Diff(
        val path: ProjectPath,
        val staged: Boolean = false,
    ) : GitOperation

    data class Stage(
        val path: ProjectPath,
    ) : GitOperation

    data class Unstage(
        val path: ProjectPath,
    ) : GitOperation

    data class Commit(
        val message: String,
        val name: String,
        val email: String,
    ) : GitOperation

    data class CreateBranch(
        val name: String,
    ) : GitOperation

    data class SwitchBranch(
        val name: String,
    ) : GitOperation

    data class SetRemote(
        val url: String,
    ) : GitOperation

    data object Fetch : GitOperation

    data object Pull : GitOperation

    data object Push : GitOperation
}

data class GitChange(
    val path: ProjectPath,
    val index: String?,
    val workingTree: String?,
)

data class GitCommit(
    val id: String,
    val message: String,
    val author: String,
    val timestampSeconds: Long,
)

data class GitStatus(
    val available: Boolean,
    val branch: String? = null,
    val head: String? = null,
    val upstream: String? = null,
    val branches: List<String> = emptyList(),
    val changes: List<GitChange> = emptyList(),
    val state: String? = null,
)

data class GitResult(
    val status: GitStatus,
    val message: String? = null,
    val diff: String? = null,
    val history: List<GitCommit> = emptyList(),
)

object GitRemote {
    fun requireHttps(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "Use an HTTPS repository URL" }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Repository URL must not contain credentials, query or fragment"
        }
    }
}
