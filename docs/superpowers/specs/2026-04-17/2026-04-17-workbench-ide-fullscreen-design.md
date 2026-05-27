# Workbench IDE Fullscreen Design

## Goal

Replace the current page-switched workbench UI with a fullscreen IDE where the editor is always primary, the terminal lives in a hideable bottom dock, one target computer slot is visible in the IDE chrome, and lifecycle actions are restored.

## Scope

This redesign applies only to the workbench IDE screen.

Included:

- fullscreen workbench IDE layout
- hideable bottom terminal dock
- visible target computer slot in the IDE surface
- restored lifecycle/control surface for the attached target computer
- removal of the old editor-versus-terminal page split for workbench
- focused bug fixes for missing slot surface and missing reboot/power control path

Excluded:

- changes to the ordinary computer screen
- terminal pane resizing in this iteration
- multiple target slots or a broader inventory redesign

## Root Causes Already Confirmed

### Missing lifecycle controls

The current workbench control surface is partially broken in code, not just hidden in the UI.

- `NetworkWorkbenchControlGateway.reboot()` is empty.
- The old toolbar still exposes a run/reboot-era surface, but it is tied to the small-window page model.
- The current workbench state model assumes `TERMINAL` and `EDITOR` are separate top-level pages.

### Missing computer slot surface

The target slot is not merely unstyled; it is disabled by the menu contract.

- `WorkbenchMenuWithoutInventory` creates placeholder slots.
- Each placeholder slot reports `isActive() = false`.
- The current screen does not allocate or render a visible slot surface for the workbench target.

### IDE not fitting the intended UX

The current `WorkbenchEditorScreen` and `WorkbenchLayoutModel` are built around a fixed-size window with hard-coded offsets for:

- top toolbar
- left workspace column
- main editor area
- separate terminal page

This architecture conflicts with the desired fullscreen IDE plus docked terminal model.

## Target UX

### Fullscreen shell

The workbench IDE becomes a fullscreen screen tied to the current Minecraft window dimensions rather than `WorkbenchTerminalMetrics`.

The shell contains:

- top IDE header with target status, target slot, and primary actions
- left sidebar for workspace navigation
- central editor area as the default main surface
- bottom status bar for document and diagnostic information
- hideable bottom terminal dock

### Terminal dock

The terminal is no longer a separate page.

- It appears as a bottom panel inside the IDE.
- It can be shown or hidden.
- The editor remains the primary surface whether the dock is visible or hidden.
- Terminal input behavior still follows the current focus rules.

### Target slot and controls

The workbench IDE header shows one visible target computer slot.

- The slot is always present in the workbench chrome.
- It communicates whether a computer is inserted.
- It sits near target metadata and lifecycle controls.

Lifecycle controls are redesigned as part of the header status area instead of the old fixed toolbar button row.

## Architecture Changes

### State model

`WorkbenchMode` should stop representing `TERMINAL` versus `EDITOR` as two separate pages for the workbench screen.

Instead, the workbench screen should be modeled as one fullscreen IDE state with UI sub-state such as:

- terminal visible or hidden
- target connected or disconnected
- active document/editor state

If useful for incremental migration, the enum may remain temporarily, but it should no longer drive full-screen page switching.

### Layout model

`WorkbenchLayoutModel` should be redesigned around window-sized bounds rather than a small framed window.

It should compute explicit regions for:

- header bounds
- sidebar bounds
- editor bounds
- status bar bounds
- terminal dock bounds when visible
- target slot bounds
- lifecycle action bounds

The current hard-coded offsets should be removed or minimized so the layout scales with fullscreen dimensions.

### Menu and slot contract

The workbench menu must expose one active target slot suitable for rendering in the IDE surface.

That requires both:

- an active slot contract in the menu
- a screen-level layout that gives the slot a stable visible position

Placeholder inactive slots should not remain the visible path for the target computer surface.

### Control gateway

Workbench lifecycle actions need a complete client-to-server path.

At minimum:

- reboot must no longer be a no-op
- the new control surface must map to real gateway actions
- the surface must reflect disconnected versus connected target state

## Interaction Model

### Header actions

The top header is responsible for:

- showing the target computer slot
- showing target connection state and label
- exposing lifecycle controls
- exposing workbench actions such as save, pull, push, run, and terminal toggle

### Terminal toggle

The old `IDE/Console` page toggle becomes a terminal visibility action.

- When hidden, the editor uses the freed vertical space.
- When shown, the editor shrinks vertically to make room for the bottom dock.

### Editor and workspace list

The existing workspace browser and editor capabilities remain, but they are re-laid out into the fullscreen shell instead of the current framed window.

## Testing And Verification

The implementation plan should include focused checks for both bug fixes and new layout behavior.

Required verification targets:

- workbench control gateway test covering reboot dispatch
- workbench menu or screen contract test proving the target slot is active and intended to be visible
- layout tests covering fullscreen editor bounds and terminal dock bounds
- compile verification for `:core` and `:v1_21_1-common`
- manual in-game verification for:
  - fullscreen editor layout
  - visible target computer slot
  - visible lifecycle controls
  - terminal dock show/hide behavior

## Non-Goals

- no redesign of ordinary computer UI
- no multi-slot workbench inventory redesign
- no terminal pane resize handle in this pass
- no unrelated editor feature expansion beyond what is required for the new IDE shell