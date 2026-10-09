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
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdePromptTest {
    @Test
    fun `project folder rename accepts a single name and preserves the catalog identity`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.RenameProject("old"), "new")
        assertEquals(
            ru.lazyhat.compukters.ide.client.state.IdeCommand
                .RenameProject("old", "new"),
            prompt.confirm(),
        )
        prompt.open(IdePromptKind.RenameProject("old"), "../escape")
        assertEquals(null, prompt.confirm())
        assertTrue(prompt.state?.error != null)
    }

    @Test
    fun `clone and commit forms emit commands only after all fields are admitted`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.CloneRemote, "https://example.com/team/demo.git")
        assertNull(prompt.confirm())
        assertEquals("demo", prompt.state?.value)
        assertEquals(IdeCommand.CloneProject("demo", "https://example.com/team/demo.git"), prompt.confirm())
        prompt.open(IdePromptKind.CloneRemote, "https://user:secret@example.com/demo.git")
        assertNull(prompt.confirm())
        assertNotNull(prompt.state?.error)
        prompt.open(IdePromptKind.GitCommitMessage, "Add program")
        assertNull(prompt.confirm())
        prompt.type("Player")
        assertNull(prompt.confirm())
        prompt.type("player@example.com")
        assertEquals(
            IdeCommand.Git(
                ru.lazyhat.compukters.ide.git.GitOperation
                    .Commit("Add program", "Player", "player@example.com"),
            ),
            prompt.confirm(),
        )
    }

    @Test
    fun `authentication masks token and preserves spaces without retaining prompt`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.GitUsername, "player")
        assertNull(prompt.confirm())
        prompt.type(" token value ")
        kotlin.test.assertFalse(prompt.state.toString().contains("token value"))
        kotlin.test.assertFalse(prompt.state!!.displayValue.contains("token value"))
        val command = prompt.confirm() as IdeCommand.SetGitCredentials
        assertNull(prompt.state)
        assertEquals(" token value ", command.credentials!!.token().concatToString())
        command.credentials!!.close()
    }

    @Test
    fun `symbol rename prompt emits refactoring not filesystem mutation`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.RenameSymbol)
        prompt.type("renamed")
        assertEquals(IdeCommand.RenameSymbol("renamed"), prompt.confirm())
        prompt.open(IdePromptKind.RenameSymbol)
        prompt.type("я".repeat(129))
        assertNull(prompt.confirm())
        assertNotNull(prompt.state?.error)
    }

    @Test
    fun `project prompt rejects blank names and emits create command`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.CreateProject, "   ")

        assertNull(prompt.confirm())
        assertNotNull(prompt.state?.error)

        prompt.type("demo")
        assertEquals(IdeCommand.CreateProject("demo"), prompt.confirm())
        assertNull(prompt.state)
    }

    @Test
    fun `rename prompt keeps source and removes supplementary characters atomically`() {
        val prompt = IdePromptController()
        val source = ProjectPath.file("src/main.kt")
        prompt.open(IdePromptKind.Rename(source), "src/new😀")

        prompt.backspace()

        assertEquals("src/new", prompt.state?.value)
        assertEquals(IdeCommand.Rename(source, ProjectPath.file("src/new")), prompt.confirm())
    }

    @Test
    fun `prompt never truncates through a surrogate pair`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.CreateText, "a".repeat(255))

        prompt.type("😀")

        assertEquals("a".repeat(255), prompt.state?.value)
    }
}
