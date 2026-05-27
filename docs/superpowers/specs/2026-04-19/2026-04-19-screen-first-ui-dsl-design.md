# Screen-First Unified UI DSL Design

## Goal

Replace the current split between pure render builders, Minecraft-side renderers, and hand-written screen input code with one screen-first UI foundation.

The new foundation must let authors describe layout, drawing, hit testing, focus, keyboard input, mouse input, and low-level custom rendering through a single DSL that feels closer to Jetpack Compose than to a scene compiler pipeline, but without compiler plugins, hidden recomposition, or magical dependency tracking.

## Why The Previous Direction Is Wrong

The previous design centered the system around a pipeline like `DSL -> Layout IR -> Render IR -> ScreenProgram -> Backend`. That direction optimizes for compilation and execution structure before it solves authoring.

That is the wrong priority for this codebase.

The real problem is not that screens lack a compiler pipeline. The real problem is that the current UI surface is split across too many places:

- authoring in pure builders such as `buildTerminalUi(...)`
- drawing in Minecraft-side helpers such as `UiRenderer`
- click, focus, and keyboard logic inside screen classes
- special rendering paths such as terminal rendering outside the DSL surface

This split makes the DSL incomplete by construction. A declarative UI layer that cannot express buttons, click handling, focus, and keyboard routing as first-class concepts is not a usable UI DSL.

The new design therefore rejects these ideas as architectural foundations:

- render-specific compiler pipelines as the main abstraction
- separate renderer or backend layers as author-facing concepts
- binding-slot APIs for ordinary dynamic values
- a render-only DSL with input bolted on later

## Core Design Principles

### One DSL, Not Separate Render And Input Surfaces

There must be one authoring model.

An element such as `Button` or `TerminalSurface` must own all of the behavior relevant to that element:

- layout
- drawing
- hover and pressed states
- hit testing
- click handling
- focus behavior
- keyboard handling when focused

The system must not split this into a render DSL plus an unrelated input DSL. That split recreates the current problem under different names.

### Screen-First Authoring

The primary entry point should be a base class such as `DslScreen` or `DslContainerScreen`.

Authors should write UI directly in the screen class through one `content()` block or equivalent screen-local DSL hook. The screen host is responsible for mapping that content to the normal Minecraft screen lifecycle:

- `renderBg`
- `render`
- `mouseClicked`
- `mouseReleased`
- `mouseMoved`
- `mouseDragged`
- `mouseScrolled`
- `keyPressed`
- `keyReleased`
- `charTyped`

The author should not need to manually keep render and input paths in sync.

### Lambda-Based Dynamics, Not Bindings

Ordinary dynamic values should be expressed through typed lambdas and DSL expressions, not through binding slot declarations.

Examples:

- `textExpr { state.statusLine }`
- `enabledWhen { state.connected }`
- `visibleWhen { state.showTerminal }`
- `modifier.clickable(enabled = { state.canActivate }) { onActivate() }`

This keeps the authoring model readable and avoids introducing an artificial binding API for values that are naturally computed from screen state.

### Explicit DSL Expressions For Dynamic Structure

Ordinary Kotlin `if` and `for` must not be the basis for structural dynamics, because without compiler support the runtime cannot reliably analyze structure or distinguish stable from dynamic subtrees.

Instead, structural dynamics must be represented through explicit DSL nodes and expressions such as:

- `if_(condition) { ... } else_ { ... }`
- `when_(expr) { ... }`
- `forEach(itemsExpr, key = { ... }) { ... }`

This keeps the dynamic surface analyzable without compiler plugins.

### Low-Level Rendering Must Stay Available

The DSL must be able to express as much ordinary Minecraft screen UI as possible, but it must also provide a direct escape hatch for special rendering.

That means authors must be able to drop to low-level rendering without leaving the DSL host model.

Examples:

- a `canvas` or `customRender` element
- a modifier such as `drawWithGuiGraphics`
- a dedicated primitive such as `terminalSurface`

The escape hatch is a requirement, not a fallback for bad design.

## High-Level Architecture

The new foundation should be organized around a small retained runtime owned by the screen host.

The important runtime concepts are:

- `DslScreen` or `DslContainerScreen`: screen host
- `UiElement`: author-facing element model
- `Modifier`: composable per-element behavior surface
- `UiExpression<T>`: explicit dynamic value surface
- `FrameModel`: computed frame representation
- `InteractionMap`: hit testing, focus, hover, and input routing data

This is intentionally not a compiler pipeline architecture.

The system may still flatten, cache, or precompute internal structures, but those are implementation details. The primary mental model must remain "a screen hosts a UI tree that describes layout, rendering, and interaction together."

## Runtime Model

Each frame is processed in three conceptual phases.

### 1. Build Or Refresh The UI Tree

The screen host evaluates `content()` and obtains a tree of `UiElement` and `UiExpression` nodes.

The runtime is allowed to keep a stable static skeleton and refresh only dynamic pieces, but the authoring API should not expose that distinction directly.

### 2. Resolve Layout And Draw Commands

The runtime performs a layout pass over the tree and produces a `FrameModel` that contains:

- resolved bounds
- ordered draw commands
- clip and z-order information
- references to low-level custom drawing callbacks where needed

This phase is where static and dynamic work may be separated internally for performance.

### 3. Build Interaction Routing And Dispatch Input

The runtime builds an `InteractionMap` that contains:

- hit regions
- element identities
- pointer capture state
- hovered target
- pressed target
- focused target
- keyboard routing ownership

Mouse events are dispatched top-down by z-order. Keyboard events first go to the focused target and then bubble to screen-level fallback when needed.

## Performance Contract

The design must stay close in cost to hand-written screen logic.

That requires a hard performance contract from the beginning.

### Allowed Cheap Work Every Frame

- evaluating scalar expressions such as text, colors, booleans, and visibility flags
- rebuilding the interaction map from already resolved bounds
- dispatching hit testing over a flat ordered region list
- executing low-level draw commands

### Work That Must Be Limited

- rebuilding large layout subtrees
- reconstructing draw lists for unchanged static regions
- remeasuring stable text unnecessarily
- walking large generic graphs when a flat cache would suffice

### Rules For First Iteration

- static subtrees may be cached
- dynamic scalar expressions may refresh every frame
- structural dynamic nodes such as `if_` and `forEach` may rebuild only the affected subtree
- text measurement should be cached when the source string is unchanged
- terminal render data may be cached when the snapshot identity or revision is unchanged

The system must not attempt full automatic dependency tracking or general-purpose diffing in the first iteration.

## Authoring Surface

### Elements In Scope For First Iteration

- `box`
- `row`
- `column`
- `stack`
- `spacer`
- `text`
- `icon`
- `rect`
- `panel`
- `button`
- `terminalSurface`
- `canvas` or `custom`

These elements are enough to rewrite the terminal screen cleanly and to establish the design vocabulary for later workbench migration.

### Modifiers In Scope For First Iteration

- `padding`
- `offset`
- `size`
- `fillMaxWidth`
- `fillMaxHeight`
- `align`
- `zIndex`
- `background`
- `border`
- `clickable`
- `hoverable`
- `focusable`
- `scrollable`
- `keyInput`
- `visibleWhen`
- `enabledWhen`
- `tooltip`

### Expressions And Structural Nodes

- `textExpr { ... }`
- `colorExpr { ... }`
- `boolExpr { ... }`
- `if_(...) { ... } else_ { ... }`
- `when_(...) { ... }`
- `forEach(..., key = { ... }) { ... }`

## Element Responsibility Model

Interactive elements must be first-class, not macros over lower-level primitives.

For example, `Button` should own:

- default layout behavior
- default drawing behavior
- hover and pressed visuals
- click activation
- keyboard activation when focused
- optional tooltip behavior

Likewise, `TerminalSurface` should own:

- terminal snapshot drawing
- focus behavior
- click-to-focus behavior
- keyboard event routing
- scroll wheel handling where applicable

This avoids the current situation where the visual tree lives in one place and the interaction logic lives elsewhere.

## Screen Host Responsibilities

`DslScreen` or `DslContainerScreen` should centralize the logic that is currently duplicated across screen classes.

Responsibilities:

- manage the UI runtime instance
- evaluate `content()` against current screen state
- run layout and draw phases
- maintain hover, press, focus, and pointer capture state
- translate Minecraft screen lifecycle callbacks into runtime events
- expose low-level screen context to custom draw and custom input handlers when necessary

The host should make common screen code smaller, not larger.

## Testing Strategy

The architecture must remain testable without requiring a full Minecraft client boot for most cases.

### Core Tests

- layout resolution tests
- expression evaluation tests
- structural dynamic node tests for `if_`, `when_`, and `forEach`
- hit testing and z-order tests
- focus routing tests
- click and keyboard dispatch tests

### Minecraft-Facing Smoke Tests

- at least one smoke test for rendering through the new screen host
- at least one smoke test for terminal input focus and key delivery

If interaction behavior cannot be validated outside Minecraft-facing glue, the foundation is too coupled.

## Rewrite Strategy

This should be treated as a rewrite-first foundation, not as a compatibility layer over the current DSL.

The first implementation target should be `ComputerTerminalScreen`.

That screen is the right proving ground because it already contains:

- static chrome
- terminal rendering
- focus management
- mouse clicks
- keyboard input
- screen-level control buttons

The rewrite order should be:

1. Introduce the new screen-first UI foundation.
2. Implement the minimum elements and modifiers required by the terminal screen.
3. Rewrite `ComputerTerminalScreen` directly on top of the new foundation.
4. Validate performance and interaction behavior.
5. Rewrite `WorkbenchEditorScreen` only after the terminal screen proves the API shape.

This design does not require preserving the current `UiNode`, `UiRenderer`, or `WorkbenchTerminalRenderer` abstractions. They should be considered legacy and removable once the new foundation is established.

## Non-Goals For First Iteration

- Compose-like recomposition engine
- compiler plugins
- AST-level dependency analysis of arbitrary Kotlin control flow
- a general renderer/backend abstraction surface
- a render-only DSL with separate input authoring
- a universal widget toolkit covering every future UI need

## Success Criteria

The first iteration is successful when all of the following are true:

- one screen can be authored through a unified screen-first DSL
- buttons and terminal surfaces are expressible without manual screen-level hit testing glue
- dynamic text and visibility do not require explicit binding-slot declarations
- structural dynamics use explicit DSL nodes rather than hidden Kotlin control flow
- low-level custom rendering is still possible inside the DSL host
- runtime cost remains close to the previous hand-written path for the terminal screen

## Impact On Existing Plans

This design supersedes the previous compiled render architecture direction described in the April 18 UI DSL design and implementation plan.

That earlier direction can still contribute useful ideas about caching and specialization, but it should no longer define the main architecture. Any future implementation plan should be based on the screen-first unified DSL described here.