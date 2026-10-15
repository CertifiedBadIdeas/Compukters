---
layout: default
title: IDE projects and Git
description: Create, open and clone client projects, track changes with Git, and deploy programs to a computer.
section: players
permalink: /IDE/
---

# IDE projects and Git

Open the IDE with **Ctrl+I** while looking at a computer, or from its terminal. The attached computer is the deployment
target; the project and its Git repository live on your own Minecraft client. These instructions describe this documentation revision; see the
[changelog]({{ '/CHANGELOG/' | relative_url }}) for published features.

## Select named peripherals

With a computer attached, open **Devices** in the workspace toolbar. The separate window lists reachable named
peripherals with 32×32 block item icons. Scroll through the cards and use **Refresh** after renaming, reconnecting or
replacing a device. Unnamed devices are omitted. Select a card to see its actions; selection itself does not edit code.
Duplicate names can be renamed and copied as plain text; lookup copying and constant insertion remain disabled
until the ambiguity is resolved.

**Rename** uses the existing network naming rules: 1–32 letters, digits, underscores or hyphens, starting with a letter.
Names are normalized to lowercase. The server checks the exact device instance, its previous name and conflicts across
the whole network, including unavailable members. Replaced devices or stale names require **Refresh**. Renaming does not
rewrite existing string constants or running programs.

**Copy name** or **Ctrl+C** while Devices has focus copies the selected device's exact name in the network as plain text,
without quotes. This works without an open source file or lookup API, including devices with duplicate names.
Click back in the editor to use Ctrl+C for the selected code again.

**Copy lookup** copies a call such as `compukter.display.TextDisplay.named("display_1")`. Click the API selector to switch
between supported types, such as `TextDisplay` and `GraphicalDisplay`. The fully qualified type avoids requiring an import;
the corresponding addon module still needs to be enabled in the project. Copying and renaming do not require an open
writable source. Older addons without lookup metadata still support rename and constant insertion.

Open a writable project Kotlin source and double-click a device card, or select it and click **Insert constant**.
The IDE inserts a string constant immediately beneath its imports, before the existing code. For example, inserting `drive_speed` adds:

```kotlin
const val DRIVE_SPEED = "drive_speed"
```

The value is the exact peripheral name. Repeated insertion reuses a matching constant; identifier collisions get
suffixes such as `DRIVE_SPEED_2`. Insertion remains an unsaved editor change and can be undone with **Ctrl+Z**.
Use the constant in your provider lookup, for example `Speedometer.named(DRIVE_SPEED)`.

## Create, open, clone and switch

**Create** makes a project beneath `<game directory>/compukters/ide/projects` with `compukter.toml` and `src/main.kt`.
**Open directory** accepts the absolute path of an existing Compukters project and registers it in place. It does not
copy your files. Opening the same directory again reuses its catalog entry.

**Clone HTTPS** asks for the repository URL and then a local project name. The repository content must pass the project filesystem checks. Its `compukter.toml` may be missing or invalid
so it can be repaired in the editor. The IDE clones into a temporary directory and publishes the
project only after validation succeeds; a failed or cancelled clone does not appear in the project list.

For a private clone, use **HTTPS token** first. Enter your Git hosting username, then the token in the masked field.
Credentials remain in this IDE session and are forgotten on close. They are not written into preferences, manifests,
locks or remote URLs, and are not sent to the Minecraft server. Use an HTTPS URL without an embedded username/password,
query or fragment. SSH transport is deferred.

With no project open, the central **Projects** panel shows saved projects alongside **Create project**, **Open directory**,
**Clone HTTPS** and **HTTPS token**. On smaller windows these actions appear beneath the list.
With a project open, click the project name in the header to choose or manage projects and access the same actions.
The start page and project menu show each folder path and distinguish IDE folders from **External** projects.
Scroll the list to reach additional projects. An external path is remembered across IDE and Minecraft restarts, so
you only need to enter it once. Listing saved folders does not read their manifests; a missing or invalid
`compukter.toml` does not hide other projects or prevent opening files. Such a project stays editable, with analysis
and compilation unavailable until its manifest is repaired and saved. When opened without a computer, a project with no `compukter.lock` resolves its local dependencies automatically.
This prepares Kotlin analysis without saving unsaved source edits. An existing lock is preserved; use **Resolve**
to review dependency updates.
Saved changes to the manifest and lock refresh analysis without reopening the project.
Modified buffers are saved before a project transition. A failed create/open/clone keeps the current project available.

## Open-file tabs

Press **Ctrl+Shift+N** to search the current project by filename or path. Wider windows also show an **Open file…**
button in the toolbar.
Search includes files in collapsed folders and shows full paths to distinguish duplicate names. It ignores case;
separate fragments with spaces to narrow a path. An empty query puts the active and open files first.
The picker shows up to 100 results; type more of the name or path to narrow a large project.
Use **Up/Down** or **Page Up/Down** to select, **Enter** or a double click to open, and **Esc** to cancel.
The query supports the same selection, clipboard and undo actions as dialog fields.

In the source editor, **Ctrl+D** duplicates the current line below and moves the caret to its copy. With a selection,
it duplicates exactly the selected text immediately after it and selects the copy; press Ctrl+D again to repeat.
Each duplication is one undo step with **Ctrl+Z** and can be restored with **Ctrl+Shift+Z** or **Ctrl+Y**.
**Alt+Up/Down** moves the current line or every line touched by the selection one position up/down. The caret and
selection follow the moved text; a selection ending at the next line's start does not include that next line.
Moving beyond the beginning or end of the file has no effect. Each move is one undo step.

In Kotlin files, **Enter** retains the current indentation and adds one indentation level after an opening brace.
Between empty braces it creates an indented blank line and moves the closing brace to its own line.
**Tab/Shift+Tab** indents/outdents all selected lines; a selection ending at the next line's start excludes that line.
**Ctrl+V** rebases a multiline code fragment's common indentation to the insertion line, preserving nested indentation
and using the document's line endings. The existing prefix before the caret supplies the first line's indentation.
Paste inside strings/comments, fragments containing raw strings, and plain text files remain literal.
Each paste or block indentation is one undo step.


Opening project files adds tabs above the editor. Click a tab to switch files while retaining its edits, caret,
selection, scrolling and undo history. The active tab has a colored underline. A **•** marks unsaved changes;
**!** marks a save conflict.
When the tabs do not fit, use the arrows or scroll over the tab bar. Switching files reveals the active tab.

Click **×** to close a file. A modified file stays open until saving succeeds. A failed save leaves it available;
a conflict lets you cancel or discard its changes and close that file. Cancel to keep editing and use Save As if needed.
Closing the last tab
leaves an empty editor without closing the IDE. Renaming a file or folder updates its tabs, and switching projects
starts a new tab list. The tab list lasts for the current IDE session.

## Workspace controls and status

The header separates the project selector, active file path and attached target. **Build** and **Run** have labels
on wider windows and become icons when space is limited; their tooltips explain the action and any unavailable state.
The status bar keeps diagnostic counts, save state and current activity in separate areas.

Click **Details** in the status bar to read all messages captured when the window opens, including full failure
messages and artifact information. Use the wheel, **Up/Down**, **Page Up/Down**, **Home** or **End** to scroll.
**Copy all** or **Ctrl+C** copies the complete text, including messages outside the visible area. **Esc**, **Enter**
or **Close** returns to the workspace. This window is read-only; typing and project shortcuts cannot edit the file
underneath it.

## Kotlin information and problems

The code editor has vertical and horizontal scrollbars when the document exceeds the visible area.
Drag a thumb to move through the file, or click its track to move one page. Scrolling does not change
the caret or selection. The thumb size and position show how much of the document is visible and where you are.

Hover a declaration or expression to see its signature, inferred type and available documentation.
Types use short Kotlin names, including nested generics, nullable types and function types. The popup shows the return type in its signature and source information below the documentation. English KDoc groups parameters, return values, exceptions and
other tags into labeled sections, with readable lists, inline code and code examples. Move the pointer into
long documentation and use the mouse wheel or drag its scrollbar to read the remaining content; scrolling there leaves the source
in place. KDoc tags, parameter subjects, references and code examples are also highlighted in the editor.

Unknown references color the complete identifier. Errors and warnings underline the exact affected source range;
a mismatched property initializer marks its value. Hover a marked token to read the overlapping problems together.
Clicking that token still places the caret normally. The Problems window and **F2**/**Shift+F2** retain problem navigation.

While entering an argument, values and function results compatible with its parameter type appear higher
in completion. Other matching values remain available. Boolean arguments offer `true`/`false`, nullable
arguments offer `null`, and enum arguments offer qualified values such as `Mode.FIRST`.

At the start of an argument, completion offers names such as `position = ` alongside matching values.
Parameters already supplied in the call are omitted; accepting a name places the caret after `= `.

Argument information opens automatically when you type `(` in Kotlin code, and **Ctrl+P** opens it manually
inside a call. The resolved signatures follow the caret; the current positional, named or vararg parameter is
highlighted. **Ctrl+↑ / Ctrl+↓** cycles through overloads without moving the caret; the choice is retained
while you edit the same call. The selected overload shows the current parameter's English KDoc when available,
with the same code/link formatting as hover. Long signatures wrap while keeping the current parameter visible;
long parameter descriptions are bounded with an ellipsis. **Esc** dismisses the popup, and typing an argument
separator can reopen it. Typing `(` or `,` inside strings or comments does not open argument information.

## Import quick fixes

Place the caret on an unresolved Kotlin name and press **Alt+Enter**. The popup lists matching imports by
fully qualified name. Use **↑ / ↓**, **Page Up / Page Down**, or click a row to choose; **Enter** adds the
selected import and **Esc** closes the popup. The existing call and caret stay in place, and **Ctrl+Z**
undoes the import in one step. Imports remain unsaved until you save the file.

Candidates come from the current project and its enabled libraries. The IDE checks that each import resolves
this reference without changing previously resolved references or introducing errors. It does not enable
addons automatically. Unsupported references, conflicting imports and projects with syntax errors receive
no action; unrelated semantic errors may remain. Editing, moving the caret or leaving the editor dismisses
pending choices.

## UI scale

Choose **Auto**, **2** or **3** beside **Scale** in the IDE header. The selection takes effect immediately and is
saved in the client configuration across IDE sessions and game restarts. It does not change Minecraft's GUI scale.
**Auto** uses 2 below 2560×1440 (including 1920×1080), and 3 at 2560×1440 or above. Fixed selections retain their chosen
scale when the window is resized. If the window becomes too small, the scale controls remain available so you can
choose **Auto** or **2**, or enlarge the window.

## Project manifest

Current projects use `format = 3`. For a project using only built-in libraries:

```toml
format = 3
name = "p1"
addons = []
```

Old `format = 1` manifests with `[modules]` are rejected with a validation error. Replace that section with
`addons`; redstone and terminal support are built in and do not need addon requirements. Keep any optional addon
requirements in `addons`, such as `["sable", "propulsion"]`. Editing the manifest preserves your source files.

## Manage project folders

Project rows have different actions depending on where their files live:

- **Rename…** changes a folder inside the IDE projects directory. Enter a single new folder name; an existing folder
  cannot be overwritten. The manifest name and file contents are unchanged, and the active project reopens with its
  remembered file and editor position.
- **Delete folder…** permanently removes an IDE-owned project folder and all its contents, including its Git repository,
  after a confirmation showing the folder path.
- **Remove from list** is the only removal action for an **External** project. Its confirmation explicitly says that
  files will be kept. The external directory is never deleted or renamed by these controls; reopening its path adds it
  to the list again.

Save modified files before renaming or removing the active project. Management controls wait for outstanding IDE
operations to finish.

## Rename a file or folder

Select the entry in the project tree, then click the **Rename** toolbar icon. Enter its destination path relative to the
project root, such as `src/automation/control.kt`. A folder rename updates the paths of its open descendant buffers.
This also supports moving entries. **Shift+F6** is a separate Kotlin symbol refactoring action.

## Editing dialog fields

Click to place the cursor, drag to select, or double-click a word. Use **Left/Right**, **Home/End** and **Shift** to
move or extend a selection; **Ctrl+Left/Right** moves by words. **Ctrl+A** selects the whole value. **Ctrl+C/X/V**
copies, cuts or pastes; **Ctrl+Z** undoes and **Ctrl+Shift+Z** or **Ctrl+Y** redoes. Long values scroll to keep the
cursor visible, and the mouse wheel scrolls the field horizontally. Pasted line breaks and tabs become spaces.
**Enter** confirms and **Esc** cancels. Token fields remain masked and do not allow copying or cutting.

## Git changes and commits

Open **Commit** using its icon on the left-hand tool stripe. For a project without a repository, use **Create Git repository**.
The **Changes** list shows modified, new and deleted files with checkboxes. Click the checkbox beside a file to include
its current saved content in the next commit; the heading selects or clears all changed files.

File names in the project tree, active-file title and Changes use Git colors: blue for modified files,
green for added files, brown for untracked files, gray for deleted files and red for conflicts. Folder colors
summarize their descendants; selection keeps a separate background.

The source editor marks added and modified lines in its left gutter; a short gray tick marks a deletion.
These markers compare the current buffer, including unsaved edits, with **HEAD**. Background inspection runs
at most once per second without saving or pausing typing. Ignored files have no new-file markers; binary files
and comparisons exceeding the configured limits omit markers.

**Commit** occupies the left Project area and leaves the source editor available on the right. Click a changed
file to preview its difference from **HEAD** in the editor area. Added lines are green and deleted lines are red.
**Source** returns to the source file. The changes list and diff scroll independently.

The left-hand stripe contains **Project**, **Commit**, **Terminal** and **Problems** icons; hover for their labels.
**Git Log** is the bottom icon. It opens recent commits in the lower tool window beneath both Project/Commit and
the editor; the commit draft is retained. Switch between **Problems** and **Git Log** using the lower window's tabs.
The log shows commit hashes, messages and authors, with its own scrolling. Local changes and visible history refresh
automatically; remote refs update only when you choose **Fetch**. **Update** and **Push** are also in the log toolbar.
Use the close button or the selected tool icon to hide the lower window. The Project icon hides or reveals the tree.
All IDE text, including menus, dialogs and tooltips, uses the bundled JetBrains Mono font.

Enter the commit message below Changes. Configure author name and email in **Settings · Git**, opened from the
left stripe or Commit's **Settings** button. The message supports multiple lines, cursor movement, selection,
paste and undo/redo. Tab and Shift+Tab move between the fields in the active window. **Commit**
includes only checked files; **Commit & Push** pushes after the commit succeeds. Ctrl+Enter commits and
Ctrl+Shift+Enter commits and pushes. There are no separate Stage/Unstage steps. Unrelated staged changes from an
external Git client remain in its index and are not included automatically.

A failed commit keeps its draft and checked paths which are still changed. A successful commit clears the message
and its file selection while retaining the author fields for the IDE session. Changing projects clears the message
and file selection. **Source** returns from diff to the source editor; Escape closes an open menu, leaves a focused
commit field, then hides Commit.

Before a foreground Git operation, the IDE saves modified open buffers. It pauses editing while the operation runs and refreshes
files, tree and analysis after operations which change the working tree. Save conflicts must be resolved first.
Git metadata is hidden from the project tree and excluded from compiler snapshots and executable deployment.
At a short viewport, scroll the panel to reach the commit fields and actions.

## Branches and remotes

Click the branch name to open its menu. **New branch** creates and switches to a local branch; choose another branch
to switch to it. These actions require a clean working tree; preserve your changes in a commit first.
**Settings · Git** groups origin, commit author and account settings; **Set origin URL** changes the origin remote.

**Fetch** downloads remote refs. **Update** accepts only a fast-forward update and requires a clean working tree. If
local and remote histories have diverged, it reports an error and preserves local HEAD, index and working files. Merge
and conflict resolution are deferred; use an external Git client for those operations, then refresh the IDE.

**Push** sends the current branch to its configured upstream, or establishes its first origin upstream. A rejected push
does not force-update the remote. **HTTPS account and token**, **Replace HTTPS token** and **Forget HTTPS token** in
**Settings · Git** manage session authentication. In the masked token window, **Check token** validates a GitHub token
with GitHub itself and shows **Token is valid** or a check error before **Save token**. This checks token validity,
not repository permissions. Clipboard whitespace at the edges is removed. Other HTTPS hosts explicitly offer saving
without this GitHub check. Tokens remain session-only.

**Cancel** requests cancellation of a running Git operation. Cancellation does not undo an operation already completed,
and a network failure during push may require Fetch/Status to inspect the remote result.

Git hooks are not executed by the IDE. Repositories must have their own real `.git` directory; linked Git worktrees,
submodules, SSH and Git LFS are outside the initial integration. Diff, history, project content and repository storage
have independent bounds. The repository storage check runs after transfers; it is not a streaming download quota.

## Run the project

Kotlin analysis and **Build** work with the IDE opened in the air, without a computer.
Attach a computer for **Deploy** or **Run**. Deployment sends the built executable through the existing
target interface. The local `.git` directory, source repository and credentials stay on the client.
See [Getting started]({{ '/GETTING-STARTED/' | relative_url }}) for the first build and execution walkthrough.
