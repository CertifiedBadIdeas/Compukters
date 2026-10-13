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

    data class RenameProject(
        val directoryName: String,
    ) : IdePromptKind

    data class RenamePeripheral(
        val name: String,
    ) : IdePromptKind

    data object RenameSymbol : IdePromptKind

    data class Rename(
        val source: ProjectPath,
    ) : IdePromptKind
}

data class IdePromptState(
    val kind: IdePromptKind,
    val value: String,
    val error: String? = null,
    val field: IdeTextFieldView = IdeTextFieldView(value, masked = kind is IdePromptKind.GitToken),
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
            is IdePromptKind.RenameProject -> "Rename project folder · ${kind.directoryName}"
            is IdePromptKind.RenamePeripheral -> "Rename device · ${kind.name}"
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
            is IdePromptKind.RenameProject -> "New directory name"
            is IdePromptKind.RenamePeripheral -> "New device name"
            IdePromptKind.RenameSymbol -> "New name"
        }
    val displayValue: String get() = if (kind is IdePromptKind.GitToken) "•".repeat(value.length.coerceAtMost(48)) else value

    override fun toString(): String = "IdePromptState(title=$title, value=$displayValue, error=$error)"
}

class IdePromptController {
    var state: IdePromptState? = null
        private set

    private var field: IdeTextField? = null

    private var authorName = ""
    private var authorEmail = ""

    fun open(
        kind: IdePromptKind,
        initial: String = "",
    ) {
        field?.close()
        val editor = IdeTextField(initial, maximum(kind), kind is IdePromptKind.GitToken)
        field = editor
        val view = editor.view()
        state = IdePromptState(kind, view.text, field = view)
    }

    private fun synchronize(clearError: Boolean = false) {
        val current = state ?: return
        val view = field?.view() ?: return
        state = current.copy(value = view.text, field = view, error = if (clearError) null else current.error)
    }

    fun type(text: String): Boolean {
        val editor = field ?: return false
        editor.type(text)
        synchronize(clearError = true)
        return true
    }

    fun keyPressed(
        event: IdeKeyInput,
        readClipboard: () -> String,
        writeClipboard: (String) -> Unit,
    ): Boolean {
        val editor = field ?: return false
        val before = editor.view().text
        editor.keyPressed(event, readClipboard, writeClipboard)
        synchronize(clearError = before != editor.view().text)
        return true
    }

    fun backspace(): Boolean = keyPressed(IdeKeyInput(IdeKeyCode.BACKSPACE), { "" }, {})

    fun admitLayout(panel: IdeRect) {
        field?.layout(IdePromptGeometry.compute(panel).field)
        synchronize()
    }

    fun press(
        x: Double,
        y: Double,
        panel: IdeRect,
        shift: Boolean,
        doubleClick: Boolean,
    ): Boolean {
        val handled = field?.press(x, y, IdePromptGeometry.compute(panel).field, shift, doubleClick) == true
        synchronize()
        return handled
    }

    fun drag(
        x: Double,
        panel: IdeRect,
    ) {
        field?.drag(x, IdePromptGeometry.compute(panel).field)
        synchronize()
    }

    fun release() {
        field?.release()
    }

    fun scroll(
        x: Double,
        y: Double,
        panel: IdeRect,
        columns: Int,
    ) {
        if (IdePromptGeometry.compute(panel).field.containsPoint(x, y)) field?.scroll(columns)
        admitLayout(panel)
    }

    fun cancel(): Boolean {
        if (state == null) return false
        field?.close()
        field = null
        state = null
        return true
    }

    fun error(message: String) {
        state = state?.copy(error = message)
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

                    is IdePromptKind.RenameProject -> {
                        require(
                            value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' },
                        ) { "Enter one directory name" }
                        IdeCommand.RenameProject(kind.directoryName, value)
                    }

                    is IdePromptKind.RenamePeripheral -> {
                        return null
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
        cancel()
        return command
    }

    private fun maximum(kind: IdePromptKind): Int =
        when (kind) {
            IdePromptKind.OpenExisting, IdePromptKind.CloneRemote, IdePromptKind.GitRemoteUrl, is IdePromptKind.GitToken -> 4096
            is IdePromptKind.RenamePeripheral -> 32
            else -> MAXIMUM_CODE_UNITS
        }

    private companion object {
        const val MAXIMUM_CODE_UNITS = 256
    }
}
