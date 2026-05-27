# UI DSL Color API Split Design

## Goal

Separate text color from container background color in the screen-first UI DSL without breaking the current worktree.

This slice should make new code explicit and readable while keeping existing `Modifier.color(...)` call sites working during migration.

## Current Problem

The current DSL uses one `Modifier.color(...)` for two different meanings:

- text foreground color
- `box` and `button` fill color

That ambiguity already caused one real bug: a colored `box` compiled differently from colored text, and the compiler had to infer intent from the element type.

## Scope

Included:

- separate modifier channels for text color and background color
- explicit DSL methods for both channels
- compiler fallback for legacy `color(...)`
- focused regression tests for new and legacy behavior

Excluded:

- border or stroke color
- theme system or palette abstraction
- alpha blending rules beyond current `Color` enum behavior
- removal of the legacy `color(...)` API in this slice

## Public API

`UiModifier` should expose:

- `textColor(value: Color)`
- `backgroundColor(value: Color)`
- existing `color(value: Color)` retained as a legacy alias

Semantic rules:

- `textColor(...)` means foreground text color only
- `backgroundColor(...)` means filled container background only
- `color(...)` remains supported during migration and is treated as a legacy per-element fallback

## Element Semantics

### Text

`UiElement.Text` should resolve color in this order:

1. `textColor`
2. legacy `color`
3. `Color.Transparent`

### Box and Button

`UiElement.Box` should resolve fill color in this order:

1. `backgroundColor`
2. legacy `color`
3. no fill, unless the element is a button and existing button styling requires a default transparent fill op

Because `button` is currently sugar over `box`, it automatically inherits the same background-color behavior.

## Compiler Behavior

The split remains compiler-local.

Required changes:

- `RenderOp.DrawText` must receive only the resolved text color
- `RenderOp.FillRect` must receive only the resolved background color
- the compiler must stop treating one modifier field as a universal color source for both text and fills

Compatibility rule:

- old DSL code using `color(...)` continues to render correctly on both text and box during this migration step

## Testing Strategy

Add focused tests for:

- `backgroundColor(...)` on `box` emits `FillRect` with the requested color
- `textColor(...)` on `text` emits `DrawText` with the requested color
- legacy `color(...)` on `box` still emits `FillRect`
- legacy `color(...)` on `text` still emits `DrawText`

Retain existing compiler and runtime regression coverage.

## Migration Plan

This is an additive step.

- existing code keeps working
- new code should prefer `textColor(...)` and `backgroundColor(...)`
- a later cleanup slice can remove legacy `color(...)` fallback after migration of the worktree code

## Success Criteria

This slice is successful when:

- new DSL code can express text color and box fill color unambiguously
- existing code using `color(...)` still works
- compiler tests prove text and fill colors are lowered through separate semantic paths
- no backend or runtime executor changes are required