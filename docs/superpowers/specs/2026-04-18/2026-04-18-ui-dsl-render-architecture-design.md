# UI DSL Render Architecture Design

## Goal

Design a new declarative UI system for game screens that supports relative layout, compiles static structure once, allows narrow dynamic bindings, and executes as a native render plan with runtime cost close to handwritten imperative rendering.

The system should feel declarative to authors and compiled to the runtime.

## Scope

This design covers:

- the new UI authoring DSL
- layout compilation
- render compilation
- runtime invalidation and execution model
- custom native render escape hatches
- migration strategy for existing screens
- practical guidelines for writing UI with the new DSL

This design does not cover:

- a general-purpose reactive runtime similar to Jetpack Compose
- a general constraint solver
- full input and focus architecture in the first iteration
- compiler plugins, code generation, or KSP-based AOT outside normal runtime initialization

## Current Problem

The current rendering code has two separate issues.

First, a large part of the UI is still handwritten directly in screen classes through `graphics.fill(...)`, `graphics.drawString(...)`, and ad hoc helper methods. That makes relative layout, reuse, and structural reasoning harder than necessary.

Second, the current DSL layer is not a real UI DSL. It is a small pure builder that returns `List<UiNode>` for terminal rendering. That model is useful as a separation of concerns, but it is too narrow and too close to a generic scene-node representation.

The new system must solve both problems without replacing them with a slower abstraction.

## Design Goals

- declarative authoring API for screens and subtrees
- relative layout primitives instead of mostly manual absolute geometry
- compile-once static structure at screen initialization time
- narrow runtime invalidation instead of full tree rebuilds
- first-class native render primitives for special surfaces such as terminals
- a custom render escape hatch for cases the DSL should not express directly
- runtime behavior close to handwritten imperative rendering
- migration path that starts small and does not require a big-bang rewrite

## Non-Goals

- node diffing
- recomposition-by-default
- implicit dependency tracking from arbitrary state reads inside DSL blocks
- a generic retained scene graph as the main runtime model
- a layout engine as complex as browser flexbox or general AutoLayout systems

## High-Level Architecture

The system is a compilation pipeline:

`DSL -> Layout IR -> Render IR -> Native Render Ops`

### DSL Layer

The DSL is the author-facing layer. It should be expressive, readable, and structural, but it is not the runtime representation.

Its responsibilities are:

- declare layout containers and leaf primitives
- express relative sizing and alignment
- declare explicit dynamic bindings
- expose first-class special primitives such as terminal surfaces
- allow narrow custom render blocks where the DSL should stop

### Layout IR

Layout IR is the first compiled form.

It is responsible only for geometry and visibility semantics:

- parent-child layout relationships
- bounds calculation
- relative sizes
- padding and alignment
- clip regions tied to layout
- visibility gates
- classification of static versus dynamic layout dependencies

Layout IR must not contain generic render logic.

### Render IR

Render IR is the second compiled form.

It is a low-level render program built from specialized primitives such as:

- fill rectangle
- stroke rectangle
- draw text
- draw glyph run
- draw terminal surface
- push clip / pop clip
- push transform / pop transform
- custom native render op

Render IR must not preserve the original authoring tree shape unless doing so is required for correctness.

### Runtime Execution

At frame time, the runtime should do only three kinds of work:

- update dynamic binding slots
- recompute only invalidated dynamic layout fragments
- execute the compiled render plan

It must not rebuild the DSL tree and should not walk a generic node graph every frame.

## Compilation Model

Each screen compiles once into a `ScreenProgram`.

`ScreenProgram` contains four conceptual segments:

- `staticLayout`
- `dynamicLayoutFragments`
- `staticRenderOps`
- `dynamicRenderFragments`

### Static Layout

Static layout contains all bounds and relationships that can be resolved at program creation time from known screen bounds and parent-relative expressions.

Examples:

- fixed padding and insets
- percentage-based regions relative only to the parent
- fixed rows and columns
- precomputed clip rectangles

### Dynamic Layout Fragments

Dynamic layout is isolated to fragments whose geometry depends on runtime state.

Examples:

- a panel that changes height when expanded
- visibility-controlled sections
- dynamic content viewport bounds
- tab-dependent regions

The compiler must build a dependency graph so that invalidation targets only affected layout fragments.

### Static Render Ops

Static render ops form the bulk of the render plan and should be stored in contiguous, specialized buffers whenever possible.

These ops should already have resolved:

- primitive kind
- static geometry references
- colors and resource handles
- clip and transform scopes
- static text shaping where applicable

### Dynamic Render Fragments

Dynamic render fragments are small and typed.

They should refer to binding slots rather than to arbitrary closures.

Examples:

- text content slot
- color slot
- visibility slot
- terminal snapshot slot
- selected-state slot

## Overhead Strategy

The architecture is intentionally chosen to avoid "optimize later" drift.

This design accepts a slightly richer compiler pipeline in exchange for lower systemic overhead at runtime.

Compared with a direct render-program DSL, this design has a slightly higher theoretical minimum cost, but it is far more likely to remain fast as the system grows because optimization is built into the model itself:

- compile once
- flatten once
- bind narrowly
- invalidate selectively
- execute contiguous specialized ops

That trade-off is preferred over a lower-level scripting style that tends to devolve into local micro-optimizations and ad hoc exceptions.

## Layout Model

The layout system should stay small and predictable.

### Bounds Space

Each layout node resolves itself inside the bounds of its parent.

Supported measurement kinds should be limited to:

- absolute pixels
- percentage of parent
- fill remaining space
- weighted share
- intrinsic min-content and max-content where supported by the leaf primitive
- min and max constraints

### Core Containers

The first version should support only these containers:

- `box`
- `row`
- `column`
- `stack`
- `dock`

This is enough for terminal screens, workbench shells, overlays, toolbars, and split regions without introducing a large layout language.

### Core Modifiers

Supported modifiers should be limited to:

- padding
- margin
- alignment
- grow / shrink or equivalent weight semantics
- min / max size
- visibility
- clip

These modifiers should compile into layout metadata, not survive as rich runtime objects.

### Relative Units

The DSL should expose explicit relative units such as:

- `px(...)`
- `percent(...)`
- `fill()`
- `weight(...)`
- `minContent()`
- `maxContent()`

It should also support anchor-style placement where needed through edge insets and centering, but not through a fully general constraint system.

### Static Versus Dynamic Classification

Every layout expression must be classified at compile time as one of:

- static
- parent-relative static
- runtime-dynamic

This classification is essential because it determines whether the layout compiler may fully pre-resolve the expression or must keep a runtime slot.

### No General Constraint Solver

The system must explicitly avoid a general solver.

The target domain is game UI, not arbitrary document layout. A small algebra of composable bounds gives better predictability, simpler optimization, and better control over runtime cost.

## Render IR Model

Render IR is a flattened or segmentable render program built from specialized primitives.

### Primitive Set

The initial primitive set should include:

- `FillRectOp`
- `StrokeRectOp`
- `DrawTextOp`
- `DrawGlyphRunOp`
- `DrawTerminalSurfaceOp`
- `PushClipOp`
- `PopClipOp`
- `PushTransformOp`
- `PopTransformOp`
- `CustomRenderOp`

### Static Fusion

The compiler should be allowed to:

- merge adjacent compatible ops
- remove unreachable or permanently hidden ops
- pre-resolve resource references
- pre-shape static text
- reduce tree-shaped authoring structure into linear execution buffers

### Typed Dynamic Slots

Dynamic data should be resolved through typed slots rather than arbitrary render closures.

Examples:

- `TextSlot`
- `ColorSlot`
- `BooleanSlot`
- `SnapshotSlot`
- `GeometrySlotRef`

The goal is to keep frame-time dispatch small and predictable.

## Custom Render Escape Hatch

The custom render hook is required, but it must be narrow.

Its contract should be:

- layout is already resolved
- final bounds are provided
- clip state is already resolved
- render context is provided
- bound values needed by the op are already resolved
- the custom op does not gain access to the original DSL tree

This keeps the escape hatch powerful enough for special rendering but too narrow to become an alternate UI framework inside the framework.

### Layout Contract For Custom Nodes

Any custom-render leaf must declare enough metadata for layout compilation:

- preferred size
- min / max size
- whether intrinsic measurement is supported
- whether runtime state can change its geometry

This prevents custom render nodes from breaking the layout compiler.

## Terminal As A First-Class Primitive

The terminal surface should not be decomposed into generic text and rectangle nodes.

Instead, the DSL should expose a terminal primitive that compiles directly into a dedicated render op such as:

- terminal bounds reference
- terminal metrics reference
- snapshot slot
- palette reference
- clip reference
- cursor mode or status flags

This is the first proof point for the new architecture because terminal rendering is exactly the kind of special surface that should become cheaper, not more abstract.

## Authoring Guidelines

The system needs explicit guidelines so authors do not accidentally defeat the architecture.

### Prefer Declarative Layout First

Use containers, relative units, and built-in primitives for all ordinary UI structure.

Do not drop into custom render just to avoid writing a small layout expression.

### Keep Bindings Narrow

Bindings should expose resolved values, not arbitrary domain objects, unless a special primitive truly requires the richer value.

Prefer:

- `binding(::titleText)`
- `binding(::statusColor)`
- `binding(::isTerminalVisible)`

Avoid:

- binding the whole screen state into a generic closure for one node

### Treat Custom Render As A Leaf

Custom render blocks should be used for rendering behavior, not for hidden layout systems.

If a block needs to compute its own subtree layout, that usually means the DSL is missing a primitive and should be extended instead.

### Make Special Surfaces First-Class

If a UI feature repeatedly appears as a complex custom render block, promote it into a dedicated DSL primitive and Render IR op.

The terminal surface is the canonical example.

### Avoid Implicit State Reads

The DSL should not allow arbitrary hidden state access during rendering.

All dynamic behavior should be visible through explicit bindings or through custom render op contracts.

### Design For Fragment Invalidation

Authors should structure UI so that localized state changes invalidate localized fragments.

Large catch-all bindings should be avoided because they destroy the benefits of selective invalidation.

## Example Authoring Shape

The exact syntax can change, but the intended shape is roughly:

```kotlin
ui {
    dock {
        top(height = px(24)) {
            row {
                text(binding(::titleText))
                spacer(fill())
                text(binding(::statusText))
            }
        }

        center {
            terminal(
                snapshot = binding(::screenSnapshot),
                focused = binding(::terminalFocused),
            )
        }

        bottom(
            height = percent(0.2f),
            visible = binding(::showDiagnostics),
        ) {
            customRender(id = "diagnostics") { bounds, context ->
                diagnosticsRenderer.render(bounds, context)
            }
        }
    }
}
```

This example is illustrative, not normative. The key requirement is structural clarity and explicit dynamic inputs.

## Migration Strategy

The first production migration should target the terminal renderer only.

That gives the project a small and high-value proving ground:

- clearly defined visuals
- existing specialized rendering behavior
- easy output comparison against the old implementation
- strong signal on whether the first-class primitive approach works

### Migration Phases

1. Build the core compiler model: DSL surface, Layout IR, Render IR, `ScreenProgram`, slot model.
2. Add the terminal primitive and compile it directly to a dedicated render op.
3. Migrate the terminal screen to the new system.
4. Add generic containers and primitive leaf ops required by larger screens.
5. Migrate the workbench shell and its non-terminal chrome.
6. Migrate advanced overlays and editor-specific renderers.
7. Remove the old `UiNode`-based DSL and handwritten rendering islands that the new model replaces.

## Success Criteria

The new UI architecture is successful when:

- static UI structure is compiled once per screen program
- dynamic updates do not rebuild the authoring tree
- runtime invalidation stays fragment-local where the state change is local
- terminal rendering is a first-class primitive, not a generic composition of scene nodes
- relative layout can express the workbench and terminal surfaces without mostly manual geometry
- custom render blocks remain rare and leaf-like
- frame-time execution cost stays close to handwritten imperative rendering

## Risks

- The DSL may become too expressive and leak general-purpose runtime semantics back into the frame path.
- The escape hatch may become overused if the primitive set is too weak.
- Intrinsic sizing can become a hidden source of complexity if applied too broadly.
- The first syntax draft may look elegant while still producing poor compile-time classification.

The design should be judged primarily by its compiled representation and invalidation behavior, not by how Compose-like the DSL appears.

## Decision Summary

The project should adopt a new UI system built around:

- declarative authoring
- `Layout IR -> Render IR` compilation
- compile-once screen programs
- typed dynamic slots
- first-class native render primitives
- narrow custom render escape hatches
- migration starting with the terminal renderer

This gives the project a declarative UI language without accepting a heavy generic UI runtime.