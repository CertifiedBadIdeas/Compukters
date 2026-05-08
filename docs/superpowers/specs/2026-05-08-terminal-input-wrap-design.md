# Terminal Input Wrap Design

## Goal

Make the bundled CKL terminal render typed input across terminal bounds instead of clipping it at the right edge.

The change must stay at the CKL ROM layer. It must not change low-level Kotlin `ScreenBuffer` write semantics or the Minecraft/UI renderer.

## Current Behavior

`rom/terminal.ck` keeps committed terminal output in `TerminalBuffer.cellsText` and keeps the in-progress command in a separate `line` variable.

- `appendText(...)` already wraps committed stdout/shell output when `col >= columns(displayId)`.
- `renderInputLine(...)` currently draws typed input only on the current row and returns once it reaches the right edge.

This means a long command typed by the user is visually clipped, even though the full command is still stored in `line` and submitted on Enter.

## Design

Keep typed input as an overlay on top of committed terminal output for this slice. Do not merge draft input into `TerminalBuffer.cellsText`.

Add CKL helpers around `renderInputLine(...)`:

- Compute how many visual rows an input string occupies from `buffer.cursorColumn` and current display columns.
- Clear every row previously occupied by the rendered input overlay.
- Render the new input string with character-level wrapping from `buffer.cursorColumn` to the next row when the current row reaches `columns(displayId)`.

The terminal main loop should track the previously rendered input string separately from the actual input line:

- `line`: the command text that will be sent to shell on Enter.
- `renderedLine`: the command text currently drawn as overlay.

When input changes, call the input renderer with both `renderedLine` and `line`, then set `renderedLine = line`.

When shell output arrives while a command is being typed, redraw the overlay after appending the output, preserving the existing behavior.

## Bounds Behavior

Horizontal wrap is based on terminal cell bounds:

- A character drawn at the last column advances to column 0 of the next row.
- Rendering stops if the overlay would go below the last visible row.

This slice does not scroll committed terminal history for draft input. That avoids mixing unsubmitted input with committed output and leaves scrollback for a later design.

## Future Scrollback Compatibility

Future hotkey scrollback should introduce explicit viewport state over committed terminal history. This slice keeps the draft input separate from committed history, which is compatible with a future model containing:

- committed history rows,
- input draft overlay,
- viewport offset,
- auto-follow-bottom behavior.

The wrapping helper shape should be reusable when rendering both input draft and future viewport rows, but this slice does not add scrollback state or hotkey handling.

## Testing

Add a ROM source regression test that checks `terminal.ck` contains:

- a previous-rendered input parameter/path for clearing stale wrapped input,
- wrapped input rendering logic that advances to the next row at `x >= cols`,
- main-loop tracking of `renderedLine`.

Run the existing ROM compile test and bundled image audit to ensure the CKL changes remain valid and image-lowerable.
