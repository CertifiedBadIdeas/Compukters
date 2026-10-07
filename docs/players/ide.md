---
layout: default
title: IDE projects and Git
description: Create, open and clone client projects, track changes with Git, and deploy programs to a computer.
section: players
permalink: /IDE/
---

# IDE projects and Git

Open the IDE with **Ctrl+I** while looking at a computer, or from its terminal. The attached computer is the deployment
target; the project and its Git repository live on your own Minecraft client. This page includes unreleased 0.5.0 work.

## Create, open, clone and switch

**Create** makes a project beneath `<game directory>/compukters/ide/projects` with `compukter.toml` and `src/main.kt`.
**Open directory** accepts the absolute path of an existing Compukters project and registers it in place. It does not
copy your files. Opening the same directory again reuses its catalog entry.

**Clone HTTPS** asks for the repository URL and then a local project name. The repository must contain a valid
`compukter.toml` at its root and admitted project content. The IDE clones into a temporary directory and publishes the
project only after validation succeeds; a failed or cancelled clone does not appear in the project list.

For a private clone, use **HTTPS token** first. Enter your Git hosting username, then the token in the masked field.
Credentials remain in this IDE session and are forgotten on close. They are not written into preferences, manifests,
locks or remote URLs, and are not sent to the Minecraft server. Use an HTTPS URL without an embedded username/password,
query or fragment. SSH transport is deferred.

Click the project name in the header to switch projects, create a new one, open another directory or clone a repository.
Modified buffers are saved before a project transition. A failed create/open/clone keeps the current project available.

## Rename a file or folder

Select the entry in the project tree, then click the **Rename** toolbar icon. Enter its destination path relative to the
project root, such as `src/automation/control.kt`. A folder rename updates the paths of its open descendant buffers.
This also supports moving entries. **Shift+F6** is a separate Kotlin symbol refactoring action.

## Git changes and commits

Open **Git** on the right-hand tool stripe. Use **Init** for a project which has no repository yet. The panel shows the
branch, upstream and changed paths with separate index and working-tree states.

- **Stage** adds the selected path to the index; **Unstage** leaves its working file intact.
- **Working diff** compares the file with the index; **Staged diff** shows what the next commit will include.
- **Commit** asks for a message, author name and email and commits the staged content. It does not stage other changes.
- **History** shows recent commits. Use **Status** to return from diff/history to the changes list or refresh it.

The Git panel scrolls with the mouse wheel or Up/Down and Page Up/Page Down. **Editor** or Escape returns to the editor.
Before a Git operation, the IDE saves modified open buffers. It pauses editing while the operation runs and refreshes
files, tree and analysis after operations which change the working tree. Save conflicts must be resolved first.
Git metadata is hidden from the project tree and excluded from compiler snapshots and executable deployment.

## Branches and remotes

**New branch** creates and switches to a local branch. Click another branch in the list to switch to it. These actions
require a clean working tree; preserve your changes in a commit first. **Origin** sets the HTTPS URL of the origin remote.

**Fetch** downloads remote refs. **Pull FF** accepts only a fast-forward update and requires a clean working tree. If
local and remote histories have diverged, it reports an error and preserves local HEAD, index and working files. Merge
and conflict resolution are deferred; use an external Git client for those operations, then refresh the IDE.

**Push** sends the current branch to its configured upstream, or establishes its first origin upstream. A rejected push
does not force-update the remote. **HTTPS token**, **Replace token** and **Forget token** manage session authentication;
**Cancel** requests cancellation of a running Git operation. Cancellation does not undo an operation already completed,
and a network failure during push may require Fetch/Status to inspect the remote result.

Git hooks are not executed by the IDE. Repositories must have their own real `.git` directory; linked Git worktrees,
submodules, SSH and Git LFS are outside the initial integration. Diff, history, project content and repository storage
have independent bounds. The repository storage check runs after transfers; it is not a streaming download quota.

## Run the project

Use **Build**, **Deploy** or **Run** with the attached computer. Deployment sends the built executable through the existing
target interface. The local `.git` directory, source repository and credentials stay on the client.
See [Getting started]({{ '/GETTING-STARTED/' | relative_url }}) for the first build and execution walkthrough.
