# Shell-Owned Enter Echo and Control Characters Design

## Goal

Fix empty Enter behavior in the ROM shell/terminal and make visible line commits program-owned instead of terminal-owned.

## Problem

`terminal.ck` currently handles Enter by sending the current input line to stdin and locally appending `line + "\n"` to the display buffer. When the line is empty, this becomes a terminal-owned bare newline. Near the bottom row it can scroll the prompt before shell output has a chance to own the result.

`shell.ck` currently ignores blank lines with `yield()`, so the visible effect of an empty Enter is mostly terminal-side cursor movement rather than shell-owned command handling.

## Scope

- Change ROM behavior in `rom/terminal.ck` and `rom/shell.ck`.
- Add CKL string literal support for `\r` and formatter preservation for `\r`/`\b` so programs can author these control characters explicitly.
- Copy bundled `.ck` resources raw during Gradle resource generation so template expansion cannot convert escape sequences into raw control bytes.
- Add source-level regressions in `RomScriptCompileTest.kt`.
- Add basic terminal control character support in `appendText()` for `\n`, `\r`, and `\b`.
- Do not add CKL bitwise operators or numeric glyph masks in this stage.
- Do not introduce `stdio-v2` or ANSI/vt100 escape parsing in this stage.

## Architecture

Terminal remains responsible for interactive input overlay before Enter. On Enter it sends the current line through stdin and clears the pending input state, but it does not commit `line + "\n"` locally.

Shell becomes responsible for committing the entered line to visible output. Immediately after `readLine(ctx)`, shell writes `line + "\n"` to stdout, then handles the trimmed command. Blank input remains a no-op command, but its newline is now shell-owned visible output and the next prompt is emitted by the normal shell loop.

`terminal.ck` treats output control characters as part of text rendering:

- `\n`: move to column 0 on the next row, scrolling if needed.
- `\r`: move to column 0 on the current row without changing rows.
- `\b`: move one column left, clear that cell in the buffer/display, and leave the cursor there.

The CKL lexer recognizes `\r` as carriage return in string literals. The formatter emits carriage return and backspace as escaped text instead of embedding raw control bytes in formatted source.

Build resource generation must not run Groovy template expansion on `.ck` files. CKL source files are program text, not metadata templates; expanding them can corrupt string escapes such as `\r`, `\b`, and `\n` before the CKL lexer sees them.

## Future Work

Numeric glyph masks should be a separate language/runtime task. It requires CKL bitwise operators and corresponding lexer, parser, semantic analysis, bytecode/runtime execution, formatter/docs, and downstream core ROM estimator updates. After that, ROM glyphs can move from row-major string masks to numeric row masks.

## Testing

Add regressions that verify:

- `terminal.ck` does not locally commit `line + "\n"` on Enter.
- `shell.ck` echoes `line + "\n"` immediately after `readLine(ctx)`.
- `terminal.ck` handles `\r` and `\b` in `appendText()`.
- CKL lexes `\r` as carriage return and the formatter preserves `\r`/`\b` escapes.
- Processed ROM resources preserve textual CKL escapes instead of raw expanded control bytes.
- Bundled ROM scripts still compile cleanly.
