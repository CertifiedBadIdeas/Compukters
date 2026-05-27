# Terminal Scrollback Hotkeys Design

## Goal

Add CKL-side terminal scrollback with `PageUp` / `PageDown` hotkeys while keeping draft input rendering separate from committed terminal history.

## Current State

The bundled terminal stores only the currently visible committed cells in `TerminalBuffer.cellsText`. Typed input is rendered as a separate overlay, and committed stdout/shell output scrolls visually, but old rows are discarded once they move above the visible display.

## Design

Extend the terminal buffer with committed history and viewport state:

- `historyCells`: fixed-width committed cells for every committed terminal row;
- `historyRows`: total committed row count in `historyCells`;
- `viewportOffset`: how many rows above the bottom viewport the user is currently inspecting;
- keep `cellsText` as the current bottom-window cache used for normal auto-follow rendering.

The terminal remains bottom-following by default:

- `viewportOffset == 0` means auto-follow bottom;
- stdout/stderr commits continue updating the bottom cache and visible display as they do now;
- `PageUp` moves the viewport upward by one page and redraws from committed history;
- `PageDown` moves it downward by one page and resumes auto-follow when the offset returns to zero.

Typed input remains an overlay:

- when `viewportOffset == 0`, redraw the input overlay normally;
- when `viewportOffset > 0`, do not draw the input overlay over historical rows;
- any local input edit (`char`, `paste`, `Backspace`, `Enter`) snaps back to bottom by setting `viewportOffset = 0` and redrawing the bottom viewport before drawing the overlay.

## Bounds And Rendering

History rows are stored with the same fixed-width cell geometry as the current display. This slice still resets the terminal buffer if the display grid geometry changes, matching current behavior.

Add a `renderViewport(...)` helper that redraws visible rows from `historyCells` using:

- bottom row window when `viewportOffset == 0`;
- earlier windows when `viewportOffset > 0`.

This keeps scrollback logic in CKL and avoids making low-level display/runtime layers stateful.

## Hotkeys

Use GLFW key codes already delivered through CKL `key` events:

- `266` for `PageUp`
- `267` for `PageDown`

The first slice does not add `Home`, `End`, command history, or mouse-wheel scrollback.

## Testing

Add ROM source regressions asserting that `terminal.ck`:

- stores `historyCells`, `historyRows`, and `viewportOffset`;
- contains viewport rendering helpers;
- handles `PageUp` / `PageDown` key codes;
- suppresses overlay redraw while scrolled up;
- snaps back to bottom on local input edits.
