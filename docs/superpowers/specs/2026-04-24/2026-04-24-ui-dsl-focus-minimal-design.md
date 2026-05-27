# UI DSL Focus — Minimal Implementation Design

> Date: 2026-04-24
> Worktree: `ui/screen-first-ui-program-merge`
> Scope: Close the open TODO around focus and keyboard routing without building a full focus system.

## Problem

The declarative UI DSL compiles clicks into `HitRegion` / `InputRoute` entries and dispatches them correctly. Keyboard handling is stubbed:

- `ScreenRuntimeExecutor.keyPressed` checks `focusedRegionId`, which is always `null`, so every key event returns `false`.
- The compiler builds a `KeyPressed` route for `terminalSurface`, but the runtime never populates `keyHandlers`.
- `FocusProgram` / `FocusTarget` types exist but nothing writes or reads them.
- `DslContainerScreen.buildExecutor()` passes a `focusHandlers = emptyMap()` argument that the executor does not accept — the code does not compile today.
- `DslContainerScreen` does not override `mouseClicked` or `keyPressed`, so the executor's input methods are never invoked by Minecraft.

The only keyboard consumer in the foreseeable roadmap is `terminalSurface`. A full focus system (Tab order, visual indicators, hover, blur handlers, multi-focus) is premature.

## Goal

A minimal focus mechanism that:

1. Unblocks keyboard input for `terminalSurface`.
2. Has a runtime shape that a richer future focus policy can slot into without breaking the DSL.
3. Adds as little surface area as possible.

Out of scope: Tab navigation, hover state, visual focus indicators, blur callbacks, focus-follows-mouse, multiple simultaneous focusables.

## Design: Implicit Single Focus

### Rule

Each compiled `ScreenProgram` contains **at most one focusable element**. If present, it is always focused for the lifetime of that program.

If the compiler encounters a second focusable element it throws `IllegalStateException("UI DSL: multiple focusable elements are not supported")` at compile time (program build time, not Kotlin compile time). This keeps the door open for a richer policy later without silently breaking today's screens.

Rationale: the only existing focusable concept is `terminalSurface`, and real screens in this codebase have exactly one terminal. Cheap guard, good error.

### DSL surface

No new public modifier is exposed. `terminalSurface` is the only element that opts in internally; it keeps its current DSL signature:

```kotlin
terminalSurface(
    snapshot = expr { state.snapshot },
    modifier = Modifier.size(128, 72),
    onKey = { keyCode -> vm.onKey(keyCode) },
)
```

The unused `onFocus` callback parameter is removed — there is no blur / focus-change event in the minimal system. `onKey` remains the only keyboard entry point.

### Core types

- `FocusProgram` is replaced with `val focusedNodeId: String?` on `ScreenProgram`. The `FocusProgram` / `FocusTarget` types are deleted (unused scaffolding).
- `InputEventType` loses `KeyPressed`. Key routing no longer goes through `InputRoute`, because there is a single focused node — the handler is keyed directly by `nodeId`.
- `InputEventType` becomes effectively `Click`-only. Kept as an enum for future extension (e.g. `MouseRelease`, `Scroll`).

### Runtime

```kotlin
class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
    private val clickHandlers: Map<String, () -> Unit>,   // keyed by handlerId
    private val keyHandler: ((Int) -> Boolean)?,          // single handler, or null
) {
    fun mouseClicked(x: Int, y: Int): Boolean { /* unchanged */ }

    fun keyPressed(keyCode: Int): Boolean =
        keyHandler?.invoke(keyCode) ?: false
}
```

`slotProvider` and `focusHandlers` parameters are removed — neither is used today.

### Compiler

When the compiler lowers a `UiElement.TerminalSurface`:

1. Emit `RenderOp.DrawTerminalSurface` as today.
2. Set `focusedNodeId = nodeId`. If `focusedNodeId` is already set → throw.
3. Register the element's `onKey` lambda under that `nodeId`.

The compiler no longer emits a `KeyPressed` route or a hit region for terminal keyboard.

Click routing for `terminalSurface` is a separate question: the terminal does not currently have an `onClick`, so it does not need a click region. If later needed, the existing `clickable` modifier composes cleanly.

### Screen integration

`DslContainerScreen` gains:

```kotlin
override fun mouseClicked(x: Double, y: Double, button: Int): Boolean =
    executor.mouseClicked(x.toInt(), y.toInt()) || super.mouseClicked(x, y, button)

override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
    executor.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers)
```

The executor is recomputed per-frame today; as a minor cleanup we cache it and rebuild only when needed (initially: every frame stays acceptable, but `mouseClicked`/`keyPressed` must use the same executor instance used for render — otherwise handler identity drifts). Simplest correct fix: lazily build `executor` once per frame inside `render`, and reuse it for the input callbacks by storing it in a field. Input callbacks that fire before the first render fall back to an empty no-op.

### What we don't build

| Feature | Why not |
|---|---|
| Tab navigation | No second focusable exists |
| Visual focus indicator | Only one focusable → no ambiguity to resolve |
| Hover state | No consumer today |
| Focus-follows-mouse | Zero consumers; re-evaluate when IDE screen is built |
| Blur / focus-change callbacks | No consumer today; `onFocus` DSL param removed |
| Multi-focus / focus groups | Guarded by compile-time error; unlock when needed |

## Forward compatibility

When a second focusable element appears (likely: `workbench` IDE with editor + sidebar), replace `focusedNodeId: String?` with a proper state object carrying the active policy (follows-mouse, click-to-focus, sticky, Tab-ordered). The `ScreenRuntimeExecutor.keyPressed` signature does not change. `DslContainerScreen` does not change. Only the compiler and executor's focus resolution change.

## Migration checklist (non-normative — full plan lives in plan doc)

1. Remove `FocusProgram` / `FocusTarget` files.
2. Remove `InputEventType.KeyPressed` and related routes.
3. Remove `UiElement.TerminalSurface.onFocus` and the stray `jdk.internal` import in `UiElement.kt`.
4. Add `focusedNodeId: String?` to `ScreenProgram`.
5. Refactor compiler to populate `focusedNodeId` and reject multi-focus.
6. Refactor `ScreenRuntimeExecutor` constructor and `keyPressed` body.
7. Cache the per-frame executor on `DslContainerScreen` and override `mouseClicked` / `keyPressed`.
8. Update `ScreenProgramCompilerTest` to cover: single focusable → `focusedNodeId` set; two focusables → throws.
