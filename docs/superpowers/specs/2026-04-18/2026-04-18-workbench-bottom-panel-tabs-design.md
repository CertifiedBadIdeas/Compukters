# Workbench Bottom Panel Tabs Design

## Goal

Replace the current fullscreen workbench IDE bottom dock behavior with a single bottom panel that supports `Terminal` and `Inventory` tabs, allows the entire panel to be hidden, and sizes each tab from its own panel properties.

## Scope

This redesign applies only to the workbench IDE screen.

Included:

- a single bottom panel inside the fullscreen workbench IDE
- `Terminal` and `Inventory` tabs inside that panel
- a hidden panel state where neither tab content is shown
- separate panel size properties for the terminal tab and the inventory tab
- terminal viewport sizing derived from the terminal tab bounds instead of the old dock bounds
- focus and hitbox updates so only the active bottom surface accepts interaction

Excluded:

- changes to the ordinary computer screen
- arbitrary drag-resizing by the player in this iteration
- additional tabs beyond `Terminal` and `Inventory`

## Current Problem

The current fullscreen workbench IDE has two different bottom concepts at the same time:

- `inventoryBounds` for the inventory surface
- `terminalDockBounds` for a separate terminal dock above it

That model caused a UX regression relative to the intended workbench flow:

- the terminal moved upward into its own docked region
- the lower area stopped behaving like the previous terminal zone
- editor height is reduced by a terminal-specific dock instead of one shared bottom surface
- terminal sizing still depends on the historical dock model instead of the visible tab area

## Target UX

### Bottom panel model

The workbench IDE keeps the fullscreen editor-first shell introduced recently, but the lower portion becomes one shared panel.

The bottom panel has three states:

- `HIDDEN`
- `INVENTORY`
- `TERMINAL`

Only one state is active at a time.

When the panel is hidden, the editor expands downward and consumes the freed space.

When the panel is visible, only the active tab content is rendered.

### Default behavior

The default visible tab when the workbench IDE opens is `Terminal`.

If the panel was hidden and the user clicks a tab button, the panel reopens directly into that tab.

Hiding the panel must not discard:

- the last active tab
- the configured panel height for each tab

### Tab-specific sizing

The bottom panel height is not a single shared constant anymore.

Instead, the workbench state owns two independent properties:

- `inventoryPanelHeight`
- `terminalPanelHeight`

The active tab decides which height is used for `bottomPanelBounds`.

This keeps inventory and terminal visually aligned to the same panel region while still allowing the terminal tab to use a different height from the inventory tab.

### Terminal behavior

The terminal is no longer rendered from a dedicated dock above inventory.

Instead:

- the terminal tab receives the shared `bottomPanelBounds`
- the terminal content area is derived from the terminal tab content bounds
- the terminal grid is fitted into that visible content area

The terminal may only accept input when:

- the bottom panel is visible
- the active tab is `TERMINAL`
- the existing terminal interaction policy says input is allowed

Switching away from the terminal tab or hiding the panel must clear terminal focus immediately.

### Inventory behavior

The player inventory stays in the bottom panel, but it becomes tab content instead of a permanently present zone.

Only the inventory tab exposes inventory slot interaction.

When the active state is `TERMINAL` or `HIDDEN`, inventory slots must not remain interactable in the same screen area.

## Architecture

### State changes

`WorkbenchState` should replace the current terminal-only visibility flag with a bottom-panel state that can express all three UX states.

Recommended model:

- `bottomPanel: WorkbenchBottomPanel`
- `inventoryPanelHeight: Int`
- `terminalPanelHeight: Int`

Where `WorkbenchBottomPanel` is an enum with:

- `HIDDEN`
- `INVENTORY`
- `TERMINAL`

`terminalVisible` becomes redundant and should be removed once all callers are migrated.

### Store responsibilities

`WorkbenchStore` becomes responsible for:

- switching active bottom tabs
- hiding and showing the panel
- preserving per-tab heights
- clearing terminal focus indirectly through the screen flow when the terminal tab stops being active

The store should expose explicit actions rather than reuse the old binary terminal toggle semantics.

Expected action surface:

- show inventory tab
- show terminal tab
- hide bottom panel

The previous `toggleTerminalVisibility()` API should be replaced or reduced to thin compatibility glue during migration.

### Layout responsibilities

`WorkbenchLayoutModel.fullscreen(...)` should compute one shared bottom panel instead of separate terminal and inventory zones.

Required layout outputs:

- `bottomPanelBounds: UiRect?`
- `bottomPanelTabStripBounds`
- `inventoryTabBounds`
- `terminalTabBounds`
- `hidePanelBounds`
- `bottomPanelContentBounds: UiRect?`

The editor height is then computed from either:

- the top of `bottomPanelBounds`, or
- the status bar area when the panel is hidden

This removes the current ambiguity where the editor is squeezed by the terminal dock first and inventory second.

### Screen responsibilities

`WorkbenchEditorScreen` should:

- render the tab strip for the bottom panel
- render only the active tab content
- position inventory slots only when the inventory tab is active
- build terminal layout only when the terminal tab is active

The screen remains the owner of terminal focus transitions because it already coordinates keyboard, mouse, and terminal input controller state.

## Rendering Rules

### Bottom panel chrome

The bottom panel should have one shared chrome and one tab strip.

The visual difference between the tabs should indicate:

- active tab
- inactive tab
- hidden panel action

The hidden state should remain discoverable through a visible control in the workbench header or bottom tab strip.

### Inventory slots

Inventory slot relocation must follow the active layout.

When inventory is not active, the screen must avoid leaving clickable slot hitboxes over unrelated UI.

Acceptable implementation strategies:

- relocate inventory slots outside the visible interaction area
- suppress slot interaction at the screen layer while non-inventory tabs are active

The implementation should choose the simplest option that remains compatible with menu behavior and test coverage.

### Terminal viewport fitting

The terminal panel, terminal surface, and terminal status bounds must be computed from the terminal tab content bounds.

The rendered character grid should be clamped to the available width and height of the terminal tab.

This is the key fix that prevents terminal size from drifting back toward the old dock assumptions.

## Error Handling And Edge Cases

- If the terminal snapshot is absent, terminal layout should still build from the terminal tab bounds using the existing fallback dimensions.
- If a tab-specific height is too small, layout should clamp it to a safe minimum so the UI remains drawable.
- If the active tab is `INVENTORY` but inventory slot layout cannot be positioned safely, the screen should fail closed by avoiding hidden clickable slots.
- If the panel is hidden, terminal focus must always be cleared.

## Testing Strategy

### Layout tests

Add layout coverage for:

- hidden panel state
- inventory panel state using `inventoryPanelHeight`
- terminal panel state using `terminalPanelHeight`
- editor height expansion when the panel is hidden

### Store tests

Add state-transition coverage for:

- default bottom panel state
- showing inventory tab
- showing terminal tab
- hiding the panel
- preserving per-tab heights during tab switches

### Screen-focused tests

Add focused tests for:

- terminal focus clearing when leaving the terminal tab
- inventory slot inactivity when the active tab is not inventory
- terminal layout using bottom panel content bounds instead of terminal dock bounds

### Manual verification

Manual verification should confirm:

- the IDE opens with the terminal tab active
- the panel can switch between `Terminal`, `Inventory`, and hidden
- terminal rendering stays inside the terminal tab frame
- the editor expands when the panel is hidden
- inventory interaction works only on the inventory tab

## Success Criteria

The redesign is successful when:

- the bottom of the workbench IDE behaves as one shared tabbed panel
- terminal no longer renders in a separate dock above inventory
- terminal size follows the terminal tab bounds
- both tabs can be hidden through an explicit panel-hide action
- the editor reclaims space when the panel is hidden
- only the active bottom surface accepts interaction