# Terminal Connecting State Design

## Goal

Replace the synthetic terminal fallback snapshot with an explicit `connecting` UI state.

The workbench terminal must distinguish between three user-visible states:

1. `PoweredOff` — the computer is off, so the user sees the existing powered-off placeholder.
2. `Connecting` — the computer is on, but the client has not received the first real screen snapshot yet.
3. `Active` — the client has a real `ScreenBufferSnapshot`, so the terminal grid is rendered normally.

This change removes the fake empty terminal grid from the first client render and prevents layout decisions from depending on invented terminal dimensions.

## Requirements

- `Connecting` appears only when the computer is on and no real screen snapshot has arrived yet.
- `PoweredOff` remains the state shown when the computer is off.
- `Connecting` renders as a placeholder, not as an empty terminal grid.
- `Connecting` shows only short text equivalent to `Connecting...`.
- `Connecting` does not show terminal size text, focus hint, or input-active text.
- `Connecting` does not accept terminal input.
- Clicking the terminal area while `Connecting` does not focus terminal input.
- The UI must stop depending on synthetic fallback snapshots in menu/container initialization.

## Architecture

Introduce an explicit terminal presentation state used by the workbench screen and terminal renderer.

Proposed state model:

- `PoweredOff`
- `Connecting`
- `Active(snapshot: ScreenBufferSnapshot)`

State mapping rules:

- `!isComputerOn` -> `PoweredOff`
- `isComputerOn && snapshot == null` -> `Connecting`
- `isComputerOn && snapshot != null` -> `Active(snapshot)`

This keeps the renderer contract honest: the renderer either receives a real snapshot, or it receives a placeholder state that does not pretend to be a usable terminal.

## Data Flow

### Client-side menu state

`AbstractComputerMenu.MenuSide.Client` should no longer require an initial fallback `ScreenBufferSnapshot`.

- The latest terminal snapshot becomes nullable until the first real update arrives.
- `updateScreenSnapshot(snapshot)` still stores the latest real snapshot.
- Opening the GUI for a running computer before the first sync arrives produces `Connecting`.

### Container payload

`ComputerContainerData` should stop fabricating a fallback snapshot when the server has no `lastScreenSnapshot`.

- The container payload should represent terminal snapshot presence explicitly.
- If the server has no snapshot yet, the client starts with `null`.
- When the first terminal sync packet arrives, the client transitions to `Active`.

### Screen coordination

`ComputerWorkbenchScreen` stays responsible for combining menu state with `isComputerOn` and producing the terminal presentation state consumed by the renderer.

The screen should also treat `Connecting` the same as `PoweredOff` for input behavior.

## Rendering

`buildTerminalUi(...)` should be updated to render from the terminal presentation state instead of an unconditional snapshot.

### PoweredOff

- Render the existing powered-off placeholder text.
- Do not render `TerminalView`.
- Do not render focus hint or input-active text.

### Connecting

- Render the same panel and window chrome used by the terminal screen.
- Render placeholder text equivalent to `Connecting...` inside the terminal surface area.
- Do not render `TerminalView`.
- Do not render size text.
- Do not render focus hint or input-active text.

### Active

- Render the real `TerminalView` using the received `ScreenBufferSnapshot`.
- Render terminal size text.
- Render focus hint only when terminal input is allowed but not focused.

## Layout And Sizing

`Connecting` must behave like a placeholder state, not like a terminal with guessed grid dimensions.

- Before the first real snapshot arrives, the UI must not derive terminal size from a fake snapshot.
- The `Connecting` state should use the same placeholder-style sizing behavior as the powered-off state.
- Once the first real snapshot arrives, the screen/layout may recompute from the real terminal width and height.

This preserves the intended meaning of the state transition:

- placeholder before data
- real terminal after data

## Input And Focus Rules

`Connecting` follows the same interaction rules as `PoweredOff`.

- Terminal input is disabled.
- Focus hint is hidden.
- Clicking the terminal area does not activate terminal focus.
- If the computer leaves `Active` and enters `PoweredOff` or `Connecting`, terminal focus is cleared.

## Edge Cases

- Opening the GUI immediately after turning the computer on should show `Connecting`, then switch to `Active` when the first snapshot arrives.
- Reboot should pass through `Connecting` again until the first post-reboot snapshot arrives.
- If the computer turns off after previously having a snapshot, the UI must show `PoweredOff`, not stale terminal contents.

## Testing

Add or update tests for the following:

- mapping from `isComputerOn` plus nullable snapshot to terminal presentation state
- `TerminalUiBuilder` rendering for `Connecting`
- hidden `TerminalView`, size text, focus hint, and input-active text in `Connecting`
- menu/container initialization starting without a fallback snapshot
- regression coverage ensuring the terminal flow no longer depends on hardcoded fallback snapshot dimensions

## Out Of Scope

- Changing the visual style of the terminal chrome
- Adding animated loaders or skeleton UI for `Connecting`
- Reworking editor-mode layout
- Client-side recalculation of terminal grid dimensions beyond removing synthetic fallback usage