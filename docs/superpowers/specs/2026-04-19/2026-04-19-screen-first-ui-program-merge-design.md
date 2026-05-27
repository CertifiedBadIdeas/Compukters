# Screen-First UI Program Merge Design

## Goal

Define a merged architecture that combines two directions already explored in the repository:

- the screen-first, Compose-like authoring DSL from the April 19 screen-first UI DSL design;
- the staged IR and backend execution model from the April 18 UI DSL render architecture worktree.

The result should keep the new authoring ergonomics while restoring the compile-once, typed IR, and backend-driven runtime shape needed for predictable performance.

## Why A Merge Is Needed

The two existing directions each solved only half of the problem.

The screen-first DSL direction solved the authoring problem:

- one UI DSL instead of split render and input code;
- authoring semantics that feel closer to Compose;
- interaction concepts such as click, focus, and keyboard routing expressed at the DSL level.

But the current prototype keeps too much work in a live runtime shape:

- rebuilding the UI tree each frame;
- rebuilding frame data each frame;
- computing hit regions directly from the live tree;
- keeping performance tied to immediate runtime evaluation.

The UI render architecture direction solved the execution-shape problem:

- typed layout IR;
- typed render IR;
- compile-once `ScreenProgram`;
- backend-based execution and selective invalidation.

But it underspecified the authoring surface:

- it leaned on bindings as the author-facing dynamic model;
- it focused on render-first primitives;
- it postponed input and focus instead of treating them as first-class architecture.

The merged design keeps the right half of each direction:

- authoring DSL from the screen-first direction;
- compiled staged program execution from the IR/backend direction.

## Relationship To Previous Designs

This design is a follow-up that reconciles two earlier designs instead of replacing them blindly.

### What Is Preserved From The April 18 Render Architecture Design

- `ScreenProgram` as a compiled artifact;
- separation between layout and render concerns;
- static versus dynamic classification;
- selective invalidation;
- narrow typed backends;
- typed specialized operations rather than a generic retained scene graph.

### What Is Replaced From The April 18 Render Architecture Design

- bindings as the primary author-facing dynamic model;
- a render-first DSL surface;
- deferring input and focus to a later phase.

### What Is Preserved From The April 19 Screen-First DSL Design

- one authoring DSL for layout, render intent, and interaction intent;
- screen-first authoring ergonomics;
- lambda-based expressions and handlers at the authoring level;
- explicit structural nodes such as `if_`, `when_`, and `forEach`.

### What Is Replaced From The April 19 Screen-First DSL Prototype

- rebuilding live UI trees each frame;
- rebuilding interaction maps from scratch each frame;
- using the live runtime tree as the main execution shape;
- treating the runtime as the central architecture instead of as an executor of compiled programs.

## High-Level Architecture

The merged system should have this shape:

`Authoring DSL -> ScreenProgramCompiler -> ScreenProgram -> ScreenRuntimeExecutor + Backend`

The key point is that the author still writes one screen-first DSL, but the compiler produces a phased `ScreenProgram` rather than a single render-only plan or a live runtime tree.

## Why A Runtime Still Exists

The design does still need a runtime, but it should be small and specialized.

The runtime is not a widget tree manager or a recomposition engine.

It exists only because some concerns are inherently execution-time concerns:

- current state values;
- current mouse and keyboard events;
- current focus owner;
- hit-testing against current bounds;
- dispatching event handler ids to live handler functions;
- executing backend drawing with current Minecraft objects.

The runtime should therefore be an executor of compiled screen programs, not the main representation of UI structure.

## Authoring Model

### Unified Authoring DSL

The author writes a screen-first DSL with:

- layout containers;
- visual primitives;
- semantic roles;
- interaction modifiers;
- explicit structural nodes;
- lambda-based expressions and handlers.

Examples of author-facing constructs:

- `box`, `row`, `column`, `stack`;
- `text`, `rect`, `terminalSurface`, `canvas`;
- `clickable`, `focusable`, `hoverable`, `scrollable`;
- `textExpr { ... }`, `boolExpr { ... }`, `colorExpr { ... }`;
- `if_`, `when_`, `forEach`.

### Lambdas Are Allowed, But Not As Structure

Lambdas are appropriate for:

- scalar dynamic values;
- event handlers;
- small dynamic text or color adapters.

Lambdas are not the structural execution model.

The compiler must derive the structure of the screen from explicit DSL nodes, not from arbitrary runtime lambda execution.

### Buttons Are Authoring Sugar, Not Engine Primitives

`button` may exist as authoring sugar, but the engine core should not require a dedicated button primitive.

The compiler should lower `button` into generic building blocks:

- layout nodes;
- visual modifiers or child primitives;
- interaction modifiers such as `clickable` and `focusable`;
- semantic role metadata such as `role = Button`.

This keeps the core IR small and general.

## Phased `ScreenProgram`

The merged `ScreenProgram` should evolve from the render-only shape into a phased artifact.

Conceptually it should contain:

- `layoutProgram`
- `renderProgram`
- `hitTestProgram`
- `inputProgram`
- `focusProgram`

These can still be stored as one immutable object, but they must remain specialized internally.

### Layout Program

Responsible for:

- static bounds;
- dynamic layout fragments;
- parent-child geometry relationships;
- clip and visibility shape;
- dynamic recomputation metadata.

### Render Program

Responsible for:

- static render ops;
- dynamic render fragments;
- terminal and native render primitives;
- typed payload slots;
- references to layout bounds.

### Hit-Test Program

Responsible for:

- compiled hit regions;
- z-order for input targeting;
- dynamic region enable/disable rules;
- mapping from pointer location to logical region id.

### Input Program

Responsible for:

- event routing rules;
- mapping event types to handler ids;
- click, key, scroll, and hover dispatch rules;
- key activation rules for semantic roles.

### Focus Program

Responsible for:

- focusable targets;
- tab order and directional traversal;
- focus transitions;
- capture rules for elements such as terminal surfaces.

## What Gets Compiled Versus What Stays Live

### Compiled Into `ScreenProgram`

- screen structure;
- layout topology;
- draw topology;
- hit regions and routing shape;
- focus topology;
- handler ids and event routing ids;
- semantic role metadata.

### Stays In Runtime Host State

- current scalar values;
- current snapshot data;
- current handler function table;
- current focused, hovered, and pressed ids;
- current event payloads.

This is the key compromise: structure is compiled, values and events stay live.

## Dynamic Model

The dynamic model should not revert to author-facing bindings.

Instead:

- authors write expressions and handlers as lambdas;
- the compiler assigns internal slot ids and handler ids;
- the runtime host evaluates expressions into slots;
- the executor consumes typed slots and routes events by handler id.

That preserves the ergonomics of the screen-first DSL while preserving the execution-shape benefits of the compiled IR model.

## Backend Model

The backend model from the April 18 direction should remain.

Backends should stay:

- typed;
- narrow;
- testable;
- Minecraft-facing only at the bottom layer.

The merged executor should sit above the backend and execute the phased `ScreenProgram`:

- apply layout updates;
- apply render updates;
- resolve hit targets;
- update focus state;
- dispatch input handlers;
- invoke backend drawing.

This keeps the backend small while avoiding a screen class that manually orchestrates everything again.

## Cost Model

The merged design is motivated by cost control as much as by API cleanliness.

The design should specifically avoid these overhead patterns from the current prototype:

- rebuilding a live UI tree every frame;
- rebuilding hit regions from scratch every frame;
- re-sorting interaction regions every frame;
- treating authoring lambdas as the runtime program.

The cost target should be:

- compile once for structure;
- update only dynamic fragments;
- keep hit-test and focus tables as compiled data where possible;
- keep backend execution typed and contiguous.

## Migration Strategy

This merged design implies a new execution target for the screen-first DSL, not a reversion to the old render-only surface.

The migration order should be:

1. Keep the screen-first authoring surface.
2. Replace the current live runtime tree with a compiler that targets phased `ScreenProgram` artifacts.
3. Preserve the backend execution shape from the IR/backend worktree.
4. Migrate the current terminal screen slice to the new compiled executor.
5. Only then migrate the larger workbench screen.

## Success Criteria

The merged design is successful when all of the following are true:

- authors still write one screen-first DSL;
- `button` and `terminalSurface` interaction semantics are preserved without manual screen glue;
- the runtime no longer depends on rebuilding a live tree every frame;
- layout and render keep the compile-once, typed IR model;
- input and focus are compiled into phased programs instead of being bolted onto screen methods;
- backend interfaces remain small and testable.
