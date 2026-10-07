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
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.git.GitCredentials
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.ide.git.GitRemote
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

sealed interface IdePromptKind {
    data object CreateProject : IdePromptKind

    data object OpenExisting : IdePromptKind

    data object CloneRemote : IdePromptKind

    data class CloneName(
        val remote: String,
    ) : IdePromptKind

    data object GitRemoteUrl : IdePromptKind

    data object GitBranch : IdePromptKind

    data object GitCommitMessage : IdePromptKind

    data class GitCommitName(
        val message: String,
    ) : IdePromptKind

    data class GitCommitEmail(
        val message: String,
        val name: String,
    ) : IdePromptKind

    data object GitUsername : IdePromptKind

    data class GitToken(
        val username: String,
    ) : IdePromptKind

    data object CreateText : IdePromptKind

    data object CreateDirectory : IdePromptKind

    data object RenameSymbol : IdePromptKind

    data class Rename(
        val source: ProjectPath,
    ) : IdePromptKind
}

data class IdePromptState(
    val kind: IdePromptKind,
    val value: String,
    val error: String? = null,
) {
    val title: String get() =
        when (val kind = kind) {
            IdePromptKind.CreateProject -> "Create project"
            IdePromptKind.OpenExisting -> "Open existing project · absolute directory"
            IdePromptKind.CloneRemote -> "Clone repository · HTTPS URL"
            is IdePromptKind.CloneName -> "Clone repository · local project name"
            IdePromptKind.GitRemoteUrl -> "Set origin · HTTPS URL"
            IdePromptKind.GitBranch -> "Create and switch branch · name"
            IdePromptKind.GitCommitMessage -> "Commit staged changes · message"
            is IdePromptKind.GitCommitName -> "Commit author · name"
            is IdePromptKind.GitCommitEmail -> "Commit author · email"
            IdePromptKind.GitUsername -> "HTTPS authentication · username"
            is IdePromptKind.GitToken -> "HTTPS token · this IDE session only"
            IdePromptKind.CreateText -> "Create text file"
            IdePromptKind.CreateDirectory -> "Create directory"
            is IdePromptKind.Rename -> "Rename ${kind.source.value} · destination path"
            IdePromptKind.RenameSymbol -> "Rename symbol · new name"
        }
    val fieldLabel: String get() =
        when (kind) {
            IdePromptKind.CreateProject, is IdePromptKind.CloneName -> "Directory name"
            IdePromptKind.OpenExisting -> "Absolute directory path"
            IdePromptKind.CloneRemote, IdePromptKind.GitRemoteUrl -> "HTTPS URL"
            IdePromptKind.GitBranch -> "Branch name"
            IdePromptKind.GitCommitMessage -> "Commit message"
            is IdePromptKind.GitCommitName -> "Author name"
            is IdePromptKind.GitCommitEmail -> "Author email"
            IdePromptKind.GitUsername -> "Username"
            is IdePromptKind.GitToken -> "Token"
            IdePromptKind.CreateText -> "File path"
            IdePromptKind.CreateDirectory -> "Directory path"
            is IdePromptKind.Rename -> "Destination path"
            IdePromptKind.RenameSymbol -> "New name"
        }
    val displayValue: String get() = if (kind is IdePromptKind.GitToken) "•".repeat(value.length.coerceAtMost(48)) else value

    override fun toString(): String = "IdePromptState(title=$title, value=$displayValue, error=$error)"
}

class IdePromptController {
    var state: IdePromptState? = null
        private set

    private var authorName = ""
    private var authorEmail = ""

    fun open(
        kind: IdePromptKind,
        initial: String = "",
    ) {
        state = IdePromptState(kind, appendBounded("", initial, maximum(kind)))
    }

    fun type(text: String): Boolean {
        val current = state ?: return false
        val admitted = appendBounded(current.value, text, maximum(current.kind))
        state = current.copy(value = admitted, error = null)
        return true
    }

    fun backspace(): Boolean {
        val current = state ?: return false
        if (current.value.isEmpty()) return true
        val end = current.value.offsetByCodePoints(current.value.length, -1)
        state = current.copy(value = current.value.substring(0, end), error = null)
        return true
    }

    fun cancel(): Boolean {
        if (state == null) return false
        state = null
        return true
    }

    fun confirm(): IdeCommand? {
        val current = state ?: return null
        val value = if (current.kind is IdePromptKind.GitToken) current.value else current.value.trim()
        if (value.isEmpty()) {
            state = current.copy(error = "Value must not be blank")
            return null
        }
        val command =
            runCatching {
                when (val kind = current.kind) {
                    IdePromptKind.CreateProject -> {
                        IdeCommand.CreateProject(value)
                    }

                    IdePromptKind.OpenExisting -> {
                        IdeCommand.ImportProject(value)
                    }

                    IdePromptKind.CloneRemote -> {
                        GitRemote.requireHttps(value)
                        open(IdePromptKind.CloneName(value), value.substringAfterLast('/').removeSuffix(".git"))
                        return null
                    }

                    is IdePromptKind.CloneName -> {
                        IdeCommand.CloneProject(value, kind.remote)
                    }

                    IdePromptKind.GitRemoteUrl -> {
                        GitRemote.requireHttps(value)
                        IdeCommand.Git(GitOperation.SetRemote(value))
                    }

                    IdePromptKind.GitBranch -> {
                        IdeCommand.Git(GitOperation.CreateBranch(value))
                    }

                    IdePromptKind.GitCommitMessage -> {
                        open(IdePromptKind.GitCommitName(value), authorName)
                        return null
                    }

                    is IdePromptKind.GitCommitName -> {
                        open(IdePromptKind.GitCommitEmail(kind.message, value), authorEmail)
                        return null
                    }

                    is IdePromptKind.GitCommitEmail -> {
                        require('@' in value && value.none { it.isWhitespace() || it == '<' || it == '>' }) { "Enter an email address" }
                        authorName = kind.name
                        authorEmail = value
                        IdeCommand.Git(GitOperation.Commit(kind.message, kind.name, value))
                    }

                    IdePromptKind.GitUsername -> {
                        open(IdePromptKind.GitToken(value))
                        return null
                    }

                    is IdePromptKind.GitToken -> {
                        val token = value.toCharArray()
                        try {
                            IdeCommand.SetGitCredentials(GitCredentials(kind.username, token))
                        } finally {
                            token.fill('\u0000')
                        }
                    }

                    IdePromptKind.CreateText -> {
                        IdeCommand.CreateText(ProjectPath.file(value))
                    }

                    IdePromptKind.CreateDirectory -> {
                        IdeCommand.CreateDirectory(ProjectPath.file(value))
                    }

                    is IdePromptKind.Rename -> {
                        IdeCommand.Rename(kind.source, ProjectPath.file(value))
                    }

                    IdePromptKind.RenameSymbol -> {
                        require(
                            value.encodeToByteArray().size <= ru.lazyhat.compukters.ide.analysis.MAX_RENAME_NAME_BYTES,
                        ) { "Name is too long" }
                        IdeCommand.RenameSymbol(value)
                    }
                }
            }.getOrElse { failure ->
                state = current.copy(error = failure.message ?: "Invalid name")
                return null
            }
        state = null
        return command
    }

    private fun maximum(kind: IdePromptKind): Int =
        when (kind) {
            IdePromptKind.OpenExisting, IdePromptKind.CloneRemote, IdePromptKind.GitRemoteUrl, is IdePromptKind.GitToken -> 4096
            else -> MAXIMUM_CODE_UNITS
        }

    private fun appendBounded(
        prefix: String,
        suffix: String,
        maximum: Int,
    ): String {
        val result = StringBuilder(minOf(maximum, prefix.length + suffix.length))
        result.append(prefix)
        var offset = 0
        while (offset < suffix.length) {
            val codePoint = suffix.codePointAt(offset)
            val units = Character.charCount(codePoint)
            if (result.length + units > maximum) break
            result.appendCodePoint(codePoint)
            offset += units
        }
        return result.toString()
    }

    private companion object {
        const val MAXIMUM_CODE_UNITS = 256
    }
}
