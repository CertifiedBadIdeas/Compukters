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

Open **Git** on the right-hand tool stripe. For a project without a repository, use **Create Git repository**.
The **Changes** tab lists modified, new and deleted files with checkboxes. Click the checkbox beside a file to include
its current saved content in the next commit; the heading selects or clears all changed files.

Click a file name to preview its difference from **HEAD**. Added lines are green and deleted lines are red. On a wide
window, the preview appears beside the list; on a narrow window, it opens the **Diff** tab. The list and the wide
preview scroll independently. **Log** shows recent commit messages and authors.

Enter the commit message, author name and email directly in the labeled fields below Changes. The message supports
multiple lines, cursor movement, selection, paste and undo/redo. Tab and Shift+Tab move between fields. **Commit**
includes only checked files; **Commit & Push** pushes after the commit succeeds. Ctrl+Enter commits and
Ctrl+Shift+Enter commits and pushes. There are no separate Stage/Unstage steps. Unrelated staged changes from an
external Git client remain in its index and are not included automatically.

A failed commit keeps its draft and checked paths which are still changed. A successful commit clears the message
and its file selection while retaining the author fields for the IDE session. Changing projects clears the message
and file selection. **Editor** returns to the source editor; Escape closes an open menu, leaves a focused commit
field, then returns to the editor.

Before a Git operation, the IDE saves modified open buffers. It pauses editing while the operation runs and refreshes
files, tree and analysis after operations which change the working tree. Save conflicts must be resolved first.
Git metadata is hidden from the project tree and excluded from compiler snapshots and executable deployment.
At a short viewport, scroll the panel to reach the commit fields and actions.

## Branches and remotes

Click the branch name to open its menu. **New branch** creates and switches to a local branch; choose another branch
to switch to it. These actions require a clean working tree; preserve your changes in a commit first.
**Repository** groups origin and account settings; **Set origin URL** changes the origin remote.

**Fetch** downloads remote refs. **Update** accepts only a fast-forward update and requires a clean working tree. If
local and remote histories have diverged, it reports an error and preserves local HEAD, index and working files. Merge
and conflict resolution are deferred; use an external Git client for those operations, then refresh the IDE.

**Push** sends the current branch to its configured upstream, or establishes its first origin upstream. A rejected push
does not force-update the remote. **HTTPS account**, **Replace HTTPS token** and **Forget HTTPS token** in the
Repository menu manage session authentication;
**Cancel** requests cancellation of a running Git operation. Cancellation does not undo an operation already completed,
and a network failure during push may require Fetch/Status to inspect the remote result.

Git hooks are not executed by the IDE. Repositories must have their own real `.git` directory; linked Git worktrees,
submodules, SSH and Git LFS are outside the initial integration. Diff, history, project content and repository storage
have independent bounds. The repository storage check runs after transfers; it is not a streaming download quota.

## Run the project

Use **Build**, **Deploy** or **Run** with the attached computer. Deployment sends the built executable through the existing
target interface. The local `.git` directory, source repository and credentials stay on the client.
See [Getting started]({{ '/GETTING-STARTED/' | relative_url }}) for the first build and execution walkthrough.
