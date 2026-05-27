# Computer Terminal-Only UI Design

## Goal

Remove IDE capabilities from computer UIs entirely and keep IDE/file-management workflows exclusively in Workbench.

After this change:
- `Workbench` is the only authoring surface;
- `Computer` is a runtime-only surface;
- file browsing, document editing, imports, and sync actions no longer exist in computer-facing UI.

## Why

The current codebase still contains two competing product models:
- the newer Workbench architecture says development should happen in Workbench;
- the old computer workbench screen still embeds editor and workspace behavior directly into computers.

This duplication causes the wrong mental model and keeps unnecessary code alive in the computer menu/network path.

The intended separation is simpler:
- Workbench develops programs for a target computer;
- Computer runs programs and exposes terminal/runtime controls.

## Product Decision

### Workbench

Workbench remains the only place for:
- code editing;
- import picker / IDE assistance;
- workspace browsing;
- file save/read flows;
- target-aware pull/push/run/attach actions.

### Computer

Computer UI becomes a separate, simplified runtime screen that contains only:
- terminal output;
- terminal input;
- power/status controls;
- any lightweight runtime status that directly supports execution.

Computer UI must not include:
- editor mode;
- file browser;
- document open/save;
- import picker;
- IDE diagnostics/completion flow.

## Architectural Shift

The split should be enforced at the Minecraft-facing integration layer, not only visually.

### Computer Path

The computer path should become terminal-only end-to-end:
- computer screen should no longer construct `WorkbenchStore`;
- computer menu should no longer carry workspace/editor remote state;
- computer network path should no longer provide workspace request/response behavior.

The computer runtime path stays intact:
- server computer lifecycle;
- power controls;
- terminal snapshot sync;
- terminal input events.

### Workbench Path

The Workbench path keeps all authoring behavior:
- `WorkbenchStore` remains in use there;
- workspace gateway/control gateway behavior remains there;
- IDE catalog and target-aware validation remain there.

This means the IDE core itself is not being removed. Only the computer-side integration is removed.

## Target UI Shape For Computers

Computers should open a distinct, simplified screen instead of reusing the current editor-oriented `ComputerWorkbenchScreen`.

This screen should:
- render terminal output using the existing terminal rendering stack;
- send input using the existing terminal input path;
- expose power/runtime controls appropriate for a computer terminal;
- avoid editor-specific layout and state machinery.

The target state is not “hide buttons in the old screen”.
The target state is “a different screen with a different responsibility”.

## Menu And Network Simplification

`AbstractComputerMenu` should be reduced to computer-runtime concerns only.

It should keep:
- terminal snapshot state;
- power/on-off sync;
- server-side computer/input ownership;
- display stack and other runtime-relevant menu data.

It should lose:
- `workspaceStateFlow`;
- workspace document/entry mutation helpers;
- any remote state fields that only exist for the editor.

If computer workspace packets are no longer consumed by any active computer UI path, they should be removed rather than left dormant.

## Scope Boundaries

### In Scope

- replace computer editor UI with a terminal-only screen;
- remove IDE/file-management behavior from computer menu integration;
- remove computer-side workspace/editor networking if unused;
- update tests and docs to match the new product boundary.

### Out Of Scope

- removing Workbench IDE behavior;
- changing VM/runtime semantics;
- changing Workbench pull/push/run/attach behavior;
- changing how terminal snapshots and terminal input fundamentally work.

## Migration Expectations

Behavior after the change:
- opening a computer gives terminal/runtime UI only;
- opening a Workbench gives authoring/IDE UI;
- managing files now requires Workbench;
- running and observing programs from the computer still works through terminal interaction.

This is a deliberate product tightening, not a backward-compatible UI alias.

## Risks

1. Computer workspace code may still be referenced by tests or support code even after UI removal.
2. Existing menu interfaces may currently expose editor-oriented surface area that becomes misleading once computer IDE is gone.
3. Documentation still refers to “computer workbench” concepts that need to be rewritten to avoid architectural drift.

## Verification Strategy

Implementation should verify all of the following:
- computer common/neoforge compile still passes;
- terminal snapshot/input flow still works for computers;
- Workbench editor/files/imports still work unchanged;
- no remaining computer UI path constructs `WorkbenchStore` or editor/workspace gateways;
- docs and test names no longer describe computers as authoring surfaces.

## Success Criteria

The design is successful when:
- the only IDE/editor UI in the product is Workbench;
- computers open a runtime-only terminal screen;
- computer menu/network code no longer carries authoring-state baggage;
- Workbench remains the single place for editing and file management.