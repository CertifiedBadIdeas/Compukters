# Workbench Separate Entity Design

## Goal

Turn Workbench into a separate in-world development entity instead of treating IDE behavior as something that lives inside the computer itself.

The new model must preserve VM-aware IDE behavior while making the separation between development and execution explicit.

## Why

The current computer-centered Workbench model is too coupled to the execution device.

That causes the wrong mental model:
- the computer looks like both the place where code is authored and the place where code is executed;
- IDE behavior risks becoming permanently tied to a live computer screen/menu;
- future expansion toward collaboration and remote workflows becomes harder because authoring and execution are mixed.

The desired model is:
- Workbench is where programs are authored;
- Computer is where programs are executed.

## First-Version Product Shape

Workbench is a separate block/entity used as a development station.

In the first version:
- the player inserts a computer item into a dedicated Workbench slot;
- that inserted computer becomes the active target descriptor for the IDE session;
- the Workbench keeps a local development workspace;
- the target computer remains the execution target;
- file exchange and execution are explicit actions.

This is intentionally not a multi-target project hub yet.

## Core Model

The system is split into three responsibilities.

### 1. Workbench Authoring Side

Workbench owns:
- local project/workspace state;
- editor session state;
- open files and unsaved changes;
- IDE features driven by the selected target profile;
- sync and execution actions exposed to the user.

Workbench does not execute user programs.

### 2. Computer Execution Side

Computer owns:
- runtime workspace used by the actual machine;
- VM/runtime execution;
- host-side runtime APIs;
- terminal output/input during execution.

Computer does not own IDE authoring state.

### 3. Sync / Execution Bridge

The bridge between them is explicit and action-based.

First-version operations:
- `pull` — copy files from the target computer into the local Workbench workspace;
- `push` — copy local Workbench files to the target computer;
- `run` — start program execution on the target computer;
- `attach terminal` — observe target terminal output and send terminal input.

There is no hidden shared filesystem between Workbench and Computer.

## Target Descriptor Model

The inserted computer item is the source of truth for target capabilities during the IDE session.

It acts as the target descriptor and determines:
- computer family/profile;
- VM-aware import catalog;
- capability-aware diagnostics;
- whether a program is compatible with the selected target;
- what actions may be performed against the target.

This solves the capability problem cleanly:
- the IDE does not guess a target profile;
- it does not operate against a global default;
- it works against a concrete target chosen by the player.

## Workspace Model

The source of truth for authoring is the local Workbench workspace.

That means:
- editing happens locally on the Workbench side;
- the target computer has its own separate file copy;
- movement between the two is explicit through `pull` and `push`.

Reasons for this choice:
- editing quality stays client-first;
- IDE responsiveness does not depend on server latency;
- authoring and execution remain conceptually separate;
- later networking can reuse the same model by changing the transport, not the design.

## Interaction Loop

Recommended first-version loop:

1. Player opens Workbench.
2. Player inserts a computer item into the Workbench target slot.
3. Workbench reads the target descriptor and configures IDE behavior accordingly.
4. Player edits files in the local Workbench workspace.
5. Player uses explicit actions:
   - `pull` from computer
   - `push` to computer
   - `run` on computer
   - `attach terminal`
6. Player can observe terminal output and send input through the Workbench UI.

## Target Disconnect Behavior

If the inserted computer is removed:
- the local Workbench project should remain available;
- target-bound actions should become unavailable;
- the UI should clearly show that the target is disconnected;
- VM-aware validation may fall back to a degraded or disabled mode because the target descriptor is gone.

The project should not disappear just because the target was removed.

This keeps the user model stable:
- local project persists;
- target attachment is optional but required for target-aware operations.

## IDE Behavior

Workbench IDE behavior should remain target-driven.

When a target computer is inserted, the IDE should derive from it:
- available imports;
- import picker contents;
- target compatibility diagnostics;
- capability summaries shown to the user.

This keeps the previously introduced VM-aware import work aligned with the new Workbench architecture.

## Terminal Behavior

First version should support full terminal interaction with the target computer through the Workbench.

That includes:
- seeing terminal output;
- sending terminal input;
- using Workbench as a remote control surface for the inserted target.

This is not just a passive log viewer.

## MVP Scope

### In Scope

- separate Workbench block/entity;
- target slot for a computer item;
- local Workbench workspace;
- target-driven VM-aware IDE behavior;
- explicit `pull`, `push`, `run`, `attach terminal` actions;
- target connected/disconnected states.

### Out of Scope

- multiple targets bound to one Workbench;
- shared editing between multiple players;
- automatic merge/conflict resolution;
- remote network/tower/modem-based targeting;
- hidden live shared filesystem editing;
- multi-computer orchestration/history hub behavior.

## UX State Model

Workbench UI must clearly separate three different states:

1. local project state;
2. target computer state;
3. sync divergence between local files and target files.

These must not be visually collapsed into one generic “project status”, otherwise the user will not understand:
- what exists only locally;
- what is already deployed to the target;
- what is currently executing on the target.

## Future Collaboration Direction

Collaboration should be added later by extending the authoring/project side, not by changing the target model.

The architecture should preserve three independent concepts:
- `Project Session` — editor/workspace side;
- `Target Descriptor` — capability/execution target side;
- `Sync/Execution Channel` — explicit bridge between them.

Later collaboration can expand `Project Session` into a shared or synchronized authoring model.

The target descriptor should remain separate, because capability awareness must still come from the selected execution target rather than from the collaboration layer.

## Architectural Consequences

The current code already contains useful groundwork:
- Workbench editor logic is mostly separated in core;
- IDE runtime catalog discovery is already abstracted away from a permanently live VM.

The remaining architectural move is to stop treating the Workbench as a computer screen variant and instead model it as its own Minecraft-facing entity with its own session lifecycle.

That implies a future implementation should likely introduce:
- separate Workbench block/entity/menu/screen flow;
- separate target-slot state and target-descriptor extraction;
- explicit sync/execution contracts between Workbench and Computer systems.

## Success Criteria

The design is successful when:
- Workbench is understood as a development station, not a mode inside the computer;
- inserted target computer defines capability-aware IDE behavior;
- local authoring remains separate from target execution;
- file transfer and execution are explicit user actions;
- terminal interaction works through Workbench;
- future collaboration can extend project authoring without redefining capability or execution semantics.
