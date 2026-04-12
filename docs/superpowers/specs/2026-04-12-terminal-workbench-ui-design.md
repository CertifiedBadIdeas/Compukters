# Terminal Workbench UI Design

## Context

The current terminal mode inside the computer workbench wastes part of the available workspace on a decorative inner area containing the `Terminal` label. This reduces the actual terminal rendering area. There is also a focus hint bug: when the computer is powered off, the hint is still shown even though terminal focus and input are unavailable.

The goal of this change is to make terminal mode use the full available workspace and to model powered-off state explicitly in the terminal UI, instead of showing an inactive terminal with misleading focus affordances.

## Goals

- Remove the decorative inner area that currently contains the `Terminal` label.
- Expand the terminal rendering area to the full available workbench terminal workspace.
- Replace the powered-off terminal view with an explicit placeholder message.
- Show the focus hint only when the terminal is active and can actually receive focus.
- Keep existing terminal input behavior unchanged while the computer is powered on.

## Non-Goals

- No redesign of editor mode.
- No changes to server-side VM or terminal protocol.
- No new terminal features such as scrollback, tabs, or alternate layouts.
- No broad refactor of workbench state handling beyond what is required for terminal UI state selection.

## Chosen Approach

Use the terminal UI DSL and renderer as the source of truth for terminal presentation state.

`ComputerWorkbenchScreen` will continue to coordinate screen lifecycle and input forwarding, but it should stop deciding terminal visuals in an ad-hoc way. Instead, it passes the current terminal snapshot, workbench geometry, power state, and focus state into the terminal UI builder. The builder then produces one of two presentation states:

- active terminal view
- powered-off placeholder view

This keeps UI-state selection close to the rendering model and prevents impossible combinations such as a powered-off terminal showing an actionable focus hint.

## Design

### 1. Terminal Presentation States

Terminal mode will have two explicit presentation branches.

#### Active terminal view

- Render the terminal using the full workbench terminal workspace.
- Remove the decorative inner band and the `Terminal` label entirely.
- Render the focus hint only when the computer is on and terminal input is not focused.

#### Powered-off placeholder view

- Do not render the terminal contents.
- Render a placeholder in the same workspace bounds used by the active terminal.
- Show a short explanatory message telling the player that the computer must be turned on first.
- Never render the focus hint in this state.

Using the same bounds in both states avoids layout jumps when the computer is turned on or off.

### 2. Layout Changes

The terminal workspace bounds should be recalculated so the active terminal occupies the full available content area for terminal mode. The previous decorative padding that existed only to host the `Terminal` text should be removed.

This is a functional layout change, not just a hidden label. The terminal renderer must receive larger effective bounds so the user gains real screen space.

### 3. Focus and Input Rules

`ComputerWorkbenchScreen` remains responsible for input gating because it already owns terminal input dispatch.

The rules are:

- terminal input may be focused only when the current mode is terminal mode and the computer is powered on;
- if the computer turns off while terminal input is focused, the screen must clear terminal focus immediately;
- powered-off placeholder clicks must not assign terminal focus;
- keyboard input, character input, mouse wheel input, and any other terminal-directed input must be blocked while the computer is powered off.

The focus hint is derived from UI state, not just from the raw `focused` flag. It is visible only when:

- the UI is in active terminal view; and
- terminal input is not focused.

This removes the current bug where the hint is displayed while its action is impossible.

### 4. Text and Localization

The existing terminal label string becomes unused for this screen.

A new localization key should be added for the powered-off placeholder message. The text should be short and direct, for example: `Computer is off. Turn it on first.`

If multiple loaders currently duplicate language resources, the placeholder text should be added consistently wherever the workbench UI strings are sourced from.

### 5. Implementation Boundaries

Expected change areas:

- `ComputerWorkbenchScreen`: input gating, focus reset, and passing power state into terminal UI building/rendering.
- terminal UI builder / renderer layer: explicit active/off-state rendering and updated terminal bounds.
- localization resources: placeholder message.
- tests for terminal UI state selection and hint visibility.

The implementation should remain focused on terminal mode. Editor-mode behavior and unrelated workbench logic should not change.

## Error Handling and Edge Cases

- If the terminal snapshot still updates briefly while the computer is transitioning off, the powered-off placeholder still wins visually.
- If the client retains stale terminal focus state during a power transition, screen logic clears it on the next tick.
- If the user clicks repeatedly on the powered-off placeholder, no terminal input should be forwarded.

## Testing Strategy

Add or update tests that cover the following behavior:

- terminal UI chooses active terminal presentation when the computer is on;
- terminal UI chooses placeholder presentation when the computer is off;
- focus hint is rendered only for active terminal without focus;
- focus hint is not rendered for powered-off state;
- terminal layout bounds reflect the expanded usable workspace.

Manual verification should confirm:

- the `Terminal` label no longer occupies workspace;
- the visible terminal area is larger than before;
- powered-off state shows explanatory placeholder text;
- powered-off state does not show the focus hint;
- turning the computer back on restores normal terminal interaction.

## Acceptance Criteria

- Terminal mode no longer reserves space for the `Terminal` label.
- The active terminal uses the full available terminal workspace.
- Powered-off state shows a placeholder instead of an inactive terminal.
- Focus hint is hidden whenever the computer is powered off.
- Terminal input behavior remains unchanged while the computer is powered on.