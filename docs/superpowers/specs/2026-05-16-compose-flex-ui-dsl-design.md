# Compose/Flex UI DSL Design

## Goal

Make the existing UI DSL usable as a general-purpose framework instead of exposing the low-level UI IR directly in every screen.

The runtime stays the same: screens still compile `UiElement` trees into `ScreenProgram` render operations, and dynamic values are still read through `Value<T>`. This slice improves the authoring API and validates it by rewriting the Serial Terminal screen away from absolute-position-heavy DSL code.

## Non-Goals

- Do not replace `ScreenProgramCompiler`, `ScreenRuntimeExecutor`, or the render op pipeline.
- Do not clone Jetpack Compose.
- Do not introduce retained widget state beyond the existing focus, hover, scroll, and screen-owned state.
- Do not rewrite Workbench UI in this slice.

## Layering

The framework has three layers:

1. `foundation`: `UiElement`, `Modifier`, `Value`, layout resolver, compiler, runtime executor.
2. `widgets`: small reusable authoring primitives such as `text`, `keySurface`, panels, and future inputs/lists.
3. `screen integration`: Minecraft-specific `DslContainerScreen` lifecycle, focus bridging, tooltips, slots, and rendering backend.

Current code mostly has layers 1 and 3. This slice starts layer 2.

## Authoring API

Add Compose/Flex-style modifier helpers:

- `fillMaxSize()`
- `fillMaxWidth()`
- `fillMaxHeight()`
- `width(px)`
- `height(px)`

These must compose correctly. For example, `Modifier.fillMaxWidth().height(14)` means "use the parent width and a fixed height".

Add `text` overloads that hide `Value<T>` for common cases:

- `text("static")`
- `text { dynamicText }`
- `text(color = SOME_COLOR) { dynamicText }`
- `text(color = { dynamicColor }) { dynamicText }`

Add `keySurface(...)` as a semantic focus/input element. It lowers to a focusable empty canvas but hides that implementation detail from screen code.

## Layout Semantics

The new size modifiers are resolved before the old `size(width, height)` fallback:

- Fixed `width`/`height` override intrinsic size on that axis.
- `fillMaxWidth`/`fillMaxHeight` resolve to the parent content size on that axis.
- `size(width, height)` remains as a compatibility shorthand for both axes.
- Existing `row`, `column`, `weight`, `align`, and `padding` semantics are preserved.

This keeps the existing runtime compatible while allowing flow-style screen code.

## Serial Terminal Target

`SerialTerminalScreen` should become a flow-style screen:

- root `column(fillMaxSize().background(...).padding(...))`;
- header `row(fillMaxWidth().height(...))` with title on the left and status on the right;
- output panel `box(fillMaxWidth().weight(1f).background(...))`;
- input line `box(fillMaxWidth().height(...).background(...))`;
- one `keySurface(fillMaxSize(), ...)` focus target for keyboard input.

Some terminal text can still use fixed offsets inside the output panel until a dedicated terminal-history widget exists. The important change is that screen-level layout uses flow primitives instead of hand-positioning every region.

## Testing

- Add layout resolver tests for `fillMaxWidth().height(...)` and `width(...).fillMaxHeight()`.
- Add DSL authoring tests for text overloads and `keySurface`.
- Update Serial Terminal architecture tests to require `keySurface` and flow-style modifiers.
- Run common Kotlin tests and compile checks.
