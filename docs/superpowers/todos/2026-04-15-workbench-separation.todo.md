# TODO: Separate Workbench Entity Brainstorm

Date: 2026-04-15
Status: Open question / brainstorming seed

## Source Question

Make Workbench a separate in-world entity and decouple it from the computer.

Reference motivation from [docs/TODOs.md](/home/lazyhat/IdeaProjects/Compukter-Kraft/docs/TODOs.md), item 8:
- IDE inside the computer feels too casual.
- Workbench should be a special object where programs are written for a concrete target computer.
- First version may use manual loading.
- Later versions may support live upload / filesystem access via networked upgrades.
- Development on Workbench should stay mostly client-side so editing quality does not depend on server connection quality.
- It should eventually be possible to run target-computer programs from the IDE in a convenient way.

## Current Reflections

### What already exists

- Workbench editor state and logic are already mostly separated in core:
  - WorkbenchStore
  - WorkbenchState
  - WorkbenchContracts
  - WorkbenchEditorSupport
- IDE runtime import discovery is already abstracted through a catalog source instead of being permanently tied to a live computer.
- The remaining coupling is mainly in the Minecraft-facing layer:
  - ComputerWorkbenchScreen
  - computer menu / container wiring
  - gateway adapters that still assume the screen is opened from a computer context.

### Architectural direction that seems right

- Workbench should become its own block/entity, not just an alternate screen on the computer.
- The computer should become a target of development, not the host of the IDE.
- The IDE/workbench side should stay client-first and should not require tight coupling to the running VM.
- A clean boundary is needed between:
  - Workbench local editing session
  - target computer selection / attachment
  - file transfer / sync operations
  - remote run / deploy actions

### Important design axes to resolve

1. What is Workbench in the first version?
- Just a separate IDE station for editing and manual upload
- IDE station plus basic target actions like deploy/run
- A broader development hub that manages several computers

2. Where does the editable workspace live?
- Only on the target computer
- Only on the Workbench side with explicit deploy
- In a clone/cache model with pull/push sync

3. How explicit should target binding be?
- One Workbench bound to one computer at a time
- Workbench can switch between known targets
- Workbench manages a small list of remembered targets

4. What is the minimum viable interaction loop?
- Open Workbench
- Select target computer
- Edit locally
- Upload manually
- Run on target

5. What should stay out of first scope?
- Live remote filesystem browsing
- Full multi-target orchestration
- Automatic sync/conflict resolution
- Network tower / modem dependency for the first offline/local version

## Initial intuition

A good first version likely keeps Workbench narrow:
- separate block
- local/client-oriented editor session
- explicit target computer selection
- explicit pull/push or upload flow
- optional run command against the selected target

That keeps the main conceptual split clean:
- computer = executes programs
- workbench = develops programs for a selected computer

## Open Questions For Later Brainstorming

- Should Workbench own a local persistent project cache, or only a temporary editing session?
- Should a Workbench be usable without any connected computer, as a standalone planning/coding station?
- Should the first version require physical proximity to the target computer?
- Is manual disk-like transfer a better first step than direct upload?
- Should target computer metadata include profile/capabilities/import catalog in the Workbench UI?

## Why this note exists

This is intentionally kept as a separate TODO-style brainstorming file so the question can be resumed later without polluting the global project TODO list.
