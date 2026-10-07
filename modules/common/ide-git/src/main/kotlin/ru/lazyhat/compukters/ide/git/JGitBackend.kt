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
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections

class JGitBackend internal constructor(
    private val limits: GitLimits,
    private val admitRemote: (String) -> Unit,
) : GitBackend {
    constructor(limits: GitLimits = GitLimits()) : this(limits, GitRemote::requireHttps)

    override fun clone(
        remote: String,
        destination: Path,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    ) {
        admitRemote(remote)
        cancellation.check()
        check(!Files.exists(destination) || (Files.isDirectory(destination) && Files.list(destination).use { !it.findAny().isPresent })) {
            "Clone destination must be empty"
        }
        val auth = provider(credentials)
        try {
            Git
                .cloneRepository()
                .setURI(remote)
                .setDirectory(destination.toFile())
                .setCloneAllBranches(false)
                .setTimeout(limits.timeoutSeconds)
                .setCredentialsProvider(auth)
                .setProgressMonitor(monitor(cancellation))
                .call()
                .use { checkStorage(it.repository, cancellation) }
            cancellation.check()
        } finally {
            auth?.clear()
        }
    }

    override fun execute(
        project: ProjectHandle,
        operation: GitOperation,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    ): GitResult {
        val auth = provider(credentials)
        try {
            return execute(project, operation, auth, cancellation)
        } finally {
            auth?.clear()
        }
    }

    private fun execute(
        project: ProjectHandle,
        operation: GitOperation,
        auth: UsernamePasswordCredentialsProvider?,
        cancellation: GitCancellation,
    ): GitResult {
        cancellation.check()
        check(project.isValid()) { "Project root changed" }
        val metadata = project.canonicalPath.resolve(".git")
        if (operation == GitOperation.Status && !Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) return GitResult(GitStatus(false))
        if (operation == GitOperation.Init) {
            check(!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) { "Project already has Git metadata" }
            Git
                .init()
                .setDirectory(project.canonicalPath.toFile())
                .setInitialBranch("main")
                .call()
                .close()
        }
        check(Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(metadata)) {
            "Use a repository with its own .git directory; linked worktrees are not supported yet"
        }
        Git.open(project.canonicalPath.toFile()).use { git ->
            check(
                git.repository.workTree
                    .toPath()
                    .toRealPath() == project.canonicalPath,
            ) { "Git working tree does not match the project" }
            checkStorage(git.repository, cancellation)
            var message: String? = null
            var diff: String? = null
            var history = emptyList<GitCommit>()
            when (operation) {
                GitOperation.Init, GitOperation.Status -> {
                    Unit
                }

                GitOperation.History -> {
                    if (git.repository.resolve(Constants.HEAD) != null) {
                        history =
                            git.log().setMaxCount(limits.history).call().map {
                                GitCommit(it.name, it.shortMessage.take(1024), it.authorIdent.name.take(256), it.commitTime.toLong())
                            }
                    }
                }

                is GitOperation.Diff -> {
                    diff = diff(git, operation, project)
                }

                is GitOperation.Stage -> {
                    ProjectTreeStore(project).scan()
                    git.add().addFilepattern(operation.path.value).call()
                    git
                        .add()
                        .addFilepattern(operation.path.value)
                        .setUpdate(true)
                        .call()
                }

                is GitOperation.Unstage -> {
                    if (git.repository.resolve(Constants.HEAD) == null) {
                        val cache = git.repository.lockDirCache()
                        try {
                            val builder = cache.builder()
                            for (index in 0 until cache.entryCount) {
                                val entry = cache.getEntry(index)
                                if (entry.pathString != operation.path.value && !entry.pathString.startsWith("${operation.path.value}/")) {
                                    builder.add(entry)
                                }
                            }
                            check(builder.commit()) { "Git index changed while unstaging" }
                        } finally {
                            cache.unlock()
                        }
                    } else {
                        git
                            .reset()
                            .setRef(Constants.HEAD)
                            .addPath(operation.path.value)
                            .call()
                    }
                }

                is GitOperation.Commit -> {
                    require(
                        operation.message.isNotBlank() && operation.message.length <= 8192,
                    ) { "Commit message must be nonempty and bounded" }
                    require(operation.name.isNotBlank() && operation.email.isNotBlank()) { "Commit author name and email are required" }
                    message =
                        withoutHooks(git) {
                            git
                                .commit()
                                .setMessage(operation.message)
                                .setAuthor(operation.name, operation.email)
                                .setCommitter(operation.name, operation.email)
                                .setNoVerify(true)
                                .setSign(false)
                                .call()
                                .name
                        }
                }

                is GitOperation.CreateBranch -> {
                    requireBranch(operation.name)
                    requireClean(git)
                    git
                        .checkout()
                        .setCreateBranch(true)
                        .setName(operation.name)
                        .call()
                }

                is GitOperation.SwitchBranch -> {
                    requireBranch(operation.name)
                    requireClean(git)
                    git.checkout().setName(operation.name).call()
                }

                is GitOperation.SetRemote -> {
                    admitRemote(operation.url)
                    val config = git.repository.config
                    config.setString("remote", "origin", "url", operation.url)
                    config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                    config.save()
                    message = "Configured origin"
                }

                GitOperation.Fetch -> {
                    val remote = remote(git.repository, push = false)
                    git
                        .fetch()
                        .setRemote(remote)
                        .setTimeout(limits.timeoutSeconds)
                        .setCredentialsProvider(auth)
                        .setProgressMonitor(monitor(cancellation))
                        .call()
                }

                GitOperation.Pull -> {
                    requireClean(git)
                    val remote = remote(git.repository, push = false)
                    val result =
                        git
                            .pull()
                            .setRemote(remote)
                            .setRebase(false)
                            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                            .setTimeout(limits.timeoutSeconds)
                            .setCredentialsProvider(auth)
                            .setProgressMonitor(monitor(cancellation))
                            .call()
                    check(
                        result.isSuccessful,
                    ) { "Pull requires fast-forward; branches diverged or checkout was rejected. Local commits were preserved." }
                    message = "Fast-forward pull completed"
                }

                GitOperation.Push -> {
                    val remote = remote(git.repository, push = true)
                    val source = git.repository.fullBranch
                    val target = git.repository.config.getString("branch", git.repository.branch, "merge") ?: source
                    val results =
                        withoutHooks(git) {
                            git
                                .push()
                                .setRefSpecs(RefSpec("$source:$target"))
                                .setRemote(remote)
                                .setTimeout(limits.timeoutSeconds)
                                .setCredentialsProvider(auth)
                                .setProgressMonitor(monitor(cancellation))
                                .call()
                        }
                    val updates = results.flatMap { it.remoteUpdates }
                    check(updates.isNotEmpty()) { "No branch was selected for push" }
                    check(updates.all { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }) {
                        "Push rejected (${updates.joinToString { it.status.name }}); fetch and inspect the remote branch"
                    }
                    if (git.repository.config.getString("branch", git.repository.branch, "merge") == null) {
                        git.repository.config.setString("branch", git.repository.branch, "remote", remote)
                        git.repository.config.setString("branch", git.repository.branch, "merge", target)
                        git.repository.config.save()
                    }
                    message = "Push completed"
                }
            }
            check(project.isValid()) { "Project root changed during Git operation" }
            checkStorage(git.repository, cancellation)
            return GitResult(status(git), message, diff, Collections.unmodifiableList(history.toList()))
        }
    }

    private fun <T> withoutHooks(
        git: Git,
        action: () -> T,
    ): T {
        val config = git.repository.config
        val original = config.getString("core", null, "hooksPath")
        val absent =
            git.repository.directory
                .toPath()
                .resolve("compukters-disabled-hooks-${java.util.UUID.randomUUID()}")
        check(!Files.exists(absent, LinkOption.NOFOLLOW_LINKS))
        config.setString("core", null, "hooksPath", absent.toString())
        return try {
            action()
        } finally {
            if (original == null) config.unset("core", null, "hooksPath") else config.setString("core", null, "hooksPath", original)
        }
    }

    private fun requireBranch(name: String) {
        require(Repository.isValidRefName("refs/heads/$name")) { "Invalid branch name" }
    }

    private fun requireClean(git: Git) {
        check(git.repository.repositoryState == RepositoryState.SAFE) { "Finish the existing Git operation first" }
        check(git.status().call().isClean) { "Commit or otherwise preserve local changes before changing branches or pulling" }
    }

    private fun remote(
        repository: Repository,
        push: Boolean,
    ): String {
        check(repository.fullBranch?.startsWith("refs/heads/") == true) { "Select a local branch before using a remote" }
        val branch = repository.branch
        val config = repository.config
        val remote =
            (if (push) config.getString("branch", branch, "pushRemote") else null)
                ?: config.getString("branch", branch, "remote") ?: "origin"
        val definition = RemoteConfig(config, remote)
        val uris = if (push && definition.pushURIs.isNotEmpty()) definition.pushURIs else definition.urIs
        check(uris.isNotEmpty()) { "Configure a remote repository first" }
        uris.forEach { admitRemote(it.toString()) }
        return remote
    }

    private fun status(git: Git): GitStatus {
        val state = git.status().call()
        val paths =
            (state.added + state.changed + state.removed + state.modified + state.missing + state.untracked + state.conflicting)
                .sorted()
        check(paths.size <= limits.files) { "Git status exceeds file limit" }
        val changes =
            paths.map { path ->
                val index =
                    when (path) {
                        in state.conflicting -> "conflict"
                        in state.added -> "added"
                        in state.changed -> "modified"
                        in state.removed -> "deleted"
                        else -> null
                    }
                val working =
                    when (path) {
                        in state.conflicting -> "conflict"
                        in state.untracked -> "untracked"
                        in state.modified -> "modified"
                        in state.missing -> "deleted"
                        else -> null
                    }
                GitChange(ProjectPath.file(path), index, working)
            }
        val branches =
            git
                .branchList()
                .call()
                .map { Repository.shortenRefName(it.name) }
                .sorted()
        check(branches.size <= limits.files) { "Git branch list exceeds limit" }
        val branch = git.repository.branch
        val remote = git.repository.config.getString("branch", branch, "remote")
        val merge = git.repository.config.getString("branch", branch, "merge")
        return GitStatus(
            true,
            branch,
            git.repository.resolve(Constants.HEAD)?.name,
            if (remote != null && merge != null) "$remote/${Repository.shortenRefName(merge)}" else null,
            Collections.unmodifiableList(branches),
            Collections.unmodifiableList(changes),
            git.repository.repositoryState.name,
        )
    }

    private fun diff(
        git: Git,
        operation: GitOperation.Diff,
        project: ProjectHandle,
    ): String {
        val contentTree = ProjectTreeStore(project).scan()
        if (!operation.staged && operation.path.value in git.status().call().untracked) {
            val entry = contentTree.entry(operation.path)
            if (entry.kind is ru.lazyhat.compukters.ide.project.tree.ProjectFileKind.Binary) {
                return "Binary file added: ${operation.path.value}"
            }
            val text =
                ru.lazyhat.compukters.ide.project.document
                    .ProjectDocumentStore(project)
                    .open(operation.path)
                    .text
            val result = "--- /dev/null\n+++ b/${operation.path.value}\n" + text.lineSequence().joinToString("\n") { "+$it" }
            check(result.toByteArray(Charsets.UTF_8).size <= limits.diffBytes) { "Diff exceeds display limit" }
            return result
        }
        val output =
            object : ByteArrayOutputStream() {
                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    check(count.toLong() + len <= limits.diffBytes) { "Diff exceeds display limit" }
                    super.write(b, off, len)
                }

                override fun write(b: Int) {
                    check(count < limits.diffBytes) { "Diff exceeds display limit" }
                    super.write(b)
                }
            }
        val command =
            git
                .diff()
                .setCached(operation.staged)
                .setPathFilter(PathFilter.create(operation.path.value))
                .setOutputStream(output)
        if (operation.staged) {
            val head = git.repository.resolve(Constants.HEAD)
            val tree =
                if (head == null) {
                    EmptyTreeIterator()
                } else {
                    CanonicalTreeParser().also { parser ->
                        RevWalk(git.repository).use { walk ->
                            git.repository.newObjectReader().use { parser.reset(it, walk.parseCommit(head).tree.id) }
                        }
                    }
                }
            command.setOldTree(tree)
        }
        command.call()
        return output.toString(Charsets.UTF_8)
    }

    private fun provider(credentials: GitCredentials?): UsernamePasswordCredentialsProvider? =
        credentials?.let { UsernamePasswordCredentialsProvider(it.username, it.token()) }

    private fun monitor(cancellation: GitCancellation): ProgressMonitor {
        val deadline = System.nanoTime() + limits.timeoutSeconds.toLong() * 1_000_000_000

        fun checkProgress() {
            cancellation.check()
            check(System.nanoTime() < deadline) { "Git operation deadline exceeded" }
        }
        return object : ProgressMonitor {
            override fun start(totalTasks: Int) = checkProgress()

            override fun beginTask(
                title: String,
                totalWork: Int,
            ) = cancellation.check()

            override fun update(completed: Int) = checkProgress()

            override fun endTask() = checkProgress()

            override fun isCancelled(): Boolean = cancellation.isCancelled || System.nanoTime() >= deadline

            override fun showDuration(enabled: Boolean) = Unit
        }
    }

    private fun checkStorage(
        repository: Repository,
        cancellation: GitCancellation,
    ) {
        var bytes = 0L
        var entries = 0
        val root = repository.directory.toPath()
        val deadline = System.nanoTime() + limits.timeoutSeconds.toLong() * 1_000_000_000
        Files.walk(root, 33).use { paths ->
            paths.forEach { path ->
                cancellation.check()
                check(System.nanoTime() < deadline) { "Git storage validation deadline exceeded" }
                check(++entries <= limits.repositoryEntries) { "Git repository metadata exceeds entry limit" }
                check(root.relativize(path).nameCount <= 32) { "Git repository metadata exceeds depth limit" }
                check(!Files.isSymbolicLink(path)) { "Git metadata contains an unsupported symbolic link" }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = Math.addExact(bytes, Files.size(path))
                    check(bytes <= limits.repositoryBytes) { "Git repository storage exceeds limit" }
                }
            }
        }
    }
}
