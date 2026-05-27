# UI DSL Alignment And Weight Design

## Goal

Extend the latest screen-first DSL in the `screen-first-ui-program-merge` worktree so authors can express container-relative layout instead of only absolute offsets.

The first supported additions are:

- `padding`
- `alignment`
- `weight`
- explicit `row` and `column` containers

This change should stay small enough to fit the current compiled `ScreenProgram` architecture without introducing a second runtime tree or a full Compose-like measurement engine.

## Current Problem

The current DSL only supports:

- absolute `offset(x, y)`
- explicit `size(width, height)`
- basic z-order and interaction modifiers
- `box`, `text`, `terminalSurface`, and `if_`

That keeps the compiler simple, but it makes authoring awkward for any layout that depends on container-relative positioning. Typical UI requirements such as centering content, adding inner spacing, or distributing remaining space cannot be expressed directly.

## Scope

This design adds a small container layout model, not a general-purpose retained UI framework.

Included:

- `padding` on containers and leaf nodes
- `align` for container-relative child placement
- `weight` for axis-based space distribution
- `row` and `column` containers
- compiler-side layout resolution before render/hit/input lowering

Excluded from this slice:

- spacing/gap between children
- min/max constraints
- intrinsic measurement
- wrap content measurement based on real text width
- baseline alignment
- percentages or flex-grow/shrink variants

## Authoring Model

### New Containers

The authoring layer should support three structural containers:

- `box { ... }`
- `row { ... }`
- `column { ... }`

Meaning:

- `box` is an overlay container with one shared content area for all children
- `row` is a horizontal axis container
- `column` is a vertical axis container

### New Modifiers

`UiModifier` should gain:

- `padding(all: Int)`
- `padding(horizontal: Int, vertical: Int)`
- `padding(left: Int, top: Int, right: Int, bottom: Int)`
- `align(value: UiAlignment)`
- `weight(value: Float)`

The modifier remains immutable and chainable like the existing API.

### Alignment Model

Use one minimal alignment enum in the first step:

- `UiAlignment.Start`
- `UiAlignment.Center`
- `UiAlignment.End`
- `UiAlignment.Stretch`

This keeps the API small. If future work needs axis-specific alignment types, they can be split later.

### Padding Model

Each container computes:

`contentBounds = ownBounds - padding`

Children are laid out only inside `contentBounds`.

Padding is therefore a layout concern, not a render-only concern.

## Semantics By Container

### Box

`box` remains the simplest container.

Rules:

- all children share the same padded content area
- `align` positions a child inside that area
- `offset` is applied after aligned placement as a local adjustment
- `weight` is accepted by the API but ignored during `box` layout

Reasoning:

The user explicitly wants alignment and weight available alongside plain `box`, but `weight` has no clean distribution semantics in an overlay container. Ignoring it in `box` is more predictable than inventing implicit fill heuristics.

### Row

Rules:

- fixed-size children consume width first
- the remaining width is divided between weighted children proportionally
- `align` controls cross-axis placement vertically
- `Stretch` expands a child across the row's cross-axis height
- `offset` is applied after row placement

### Column

Rules:

- fixed-size children consume height first
- the remaining height is divided between weighted children proportionally
- `align` controls cross-axis placement horizontally
- `Stretch` expands a child across the column's cross-axis width
- `offset` is applied after column placement

## Weight Semantics

`weight` only participates in layout inside `row` and `column`.

Rules:

- `weight(value <= 0)` is invalid and should fail fast via `require`
- weighted children divide only the remaining primary-axis space after fixed children are placed
- weighted children receive the full allocated primary-axis span in this first slice
- any non-fill variant is explicitly deferred until a later step

This keeps semantics strict and avoids partially-implemented API.

## Compiler Architecture

The current compiler directly lowers authoring nodes into absolute `LayoutNode`s, render ops, hit regions, and input routes.

That is no longer sufficient once child geometry depends on parent layout behavior.

### Required Change

Introduce a dedicated layout resolution pass before the existing lowering stages.

Suggested shape:

- `UiLayoutResolver`
- input: authoring tree + root bounds
- output: resolved bounds per node id

Then `ScreenProgramCompiler` becomes a two-phase compiler:

1. resolve final bounds for all nodes
2. lower those resolved bounds into:
   - `LayoutProgram`
   - `RenderProgram`
   - `HitTestProgram`
   - `InputProgram`
   - `FocusProgram`

### Why This Is The Right Boundary

- the runtime executor does not need to learn row/column rules
- hit-testing becomes automatically consistent with rendered geometry
- render and input lowering keep using the same compiled layout data
- layout complexity stays isolated in one pure component

## Data Flow

The new flow should be:

`Authoring DSL -> UiLayoutResolver -> resolved bounds -> ScreenProgramCompiler lowering -> ScreenProgram -> ScreenRuntimeExecutor`

More concretely:

1. DSL builds an authoring tree with `box`, `row`, `column`, and modifiers.
2. `UiLayoutResolver` traverses the tree and computes final bounds for every node.
3. The compiler uses those bounds to emit layout nodes, render ops, hit regions, and focus targets.
4. The runtime executor consumes only compiled geometry, not layout rules.

## Error Handling

The first implementation should prefer strict failures over silent magic.

Use `require` for:

- negative padding values
- `weight <= 0`
- `weight` on a child whose parent cannot legally distribute axis space, if we choose validation instead of ignore

Recommended behavior:

- invalid padding: fail
- invalid weight value: fail
- weight in `box`: ignore, but do not fail

That combination preserves the requested API shape without introducing noisy runtime errors in overlay layouts.

## Testing Strategy

Add compiler-level tests for:

- `box` centers a child inside padded content bounds
- `box` ignores `weight`
- `row` distributes remaining width across weighted children
- `column` distributes remaining height across weighted children
- container padding reduces the distributable space
- aligned child bounds propagate correctly into hit regions

Add executor-level regression coverage for:

- hit-testing a weighted child after resolved layout

Run focused compilation for common-side screens after the change so existing terminal screen code still compiles against the expanded DSL.

## Compatibility And Migration

Existing absolute-offset code should remain valid.

The migration model is additive:

- current `box`, `text`, and `terminalSurface` code continues to work
- `row`, `column`, `padding`, `align`, and `weight` become available for new layouts
- current terminal screen code does not need to adopt the new layout primitives immediately

## Success Criteria

The slice is successful when:

- authors can center content inside a padded `box`
- authors can distribute remaining space with `weight` in `row` and `column`
- the compiler still emits one consistent set of bounds used by render and hit-testing
- current compiled-screen architecture stays intact
- the public API remains small and understandable