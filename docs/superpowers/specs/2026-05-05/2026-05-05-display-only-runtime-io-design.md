# Display-only runtime I/O design

Date: 2026-05-05
Status: Draft for review

## Context

Compukter Kraft currently has two output paths for runtime computers:

1. A legacy terminal/stdout path:
   - runtime terminal sessions;
   - stdout byte broadcasting;
   - client-side terminal buffers/surfaces;
   - workbench attach-terminal behavior.
2. A newer display/framebuffer path:
   - display attach/resize/detach;
   - display frame deltas;
   - client-side framebuffer rendering.

The desired direction is to remove stdin/stdout broadcasting from the client-server model after the framebuffer/display render foundation is available.

## Decisions

- Do the implementation in a separate git worktree.
- Fully remove terminal/stdout transport from the client-server runtime UI model.
- Do not keep VM-side stdout/terminal APIs as the final internal model.
- Temporarily remove or disable workbench attach-terminal behavior instead of porting it in this iteration.
- Use a staged internal cleanup approach, not a single big-bang rewrite.
- Use a ROM-side dirty-line renderer for interactive terminal rendering instead of full framebuffer redraw per keypress.

## Goals

- Make display frames the only server-to-client output path for runtime computer UI.
- Keep client-to-server input as discrete events: key, char, paste, mouse.
- Remove stdout byte broadcast packets and terminal session management from the runtime client-server model.
- Move ROM terminal behavior to framebuffer rendering.
- Preserve shell usability: prompt, typed input, paste, backspace, Enter, and shell output.
- Avoid the previous performance regression caused by framebuffer redraw per keypress.
- Keep workbench IDE/sync/run behavior working while temporarily removing live attach-terminal viewing.

## Non-goals

- Do not introduce a new network stdin/stdout stream under another name.
- Do not implement a full workbench display viewer in this iteration.
- Do not rely on existing user workspace ROM support scripts being refreshed automatically; that behavior was reverted and must be treated separately if needed.
- Do not preserve user-facing runtime UI through `stdout_bytes` compatibility.

## Target architecture

### Client to server

The client sends only input events for a running computer:

- key down/up events;
- character input events;
- paste events;
- mouse events.

These events enter the VM event queue. They are not stdin bytes.

### Server to client

The server sends only display updates for runtime UI:

- display attach/resize/detach session management;
- framebuffer frame deltas.

There is no stdout byte stream, terminal session stream, or terminal surface stream.

### VM and ROM

The ROM terminal owns text UI behavior:

- consumes VM events with `events::tryPull`;
- sends shell commands through VM-local IPC;
- receives shell output through VM-local IPC;
- draws prompt, current line, and shell output through display/framebuffer APIs.

The shell remains independent of the client-server transport. It talks to the ROM terminal through VM-local mechanisms, not network stdin/stdout.

## Component changes

### Remove from client-server runtime UI

- `TerminalNetworkBridge`.
- `StdoutBytesClientMessage`.
- Terminal attach/resize server messages for runtime terminal sessions.
- `RuntimeDeviceTerminalSessions`.
- `RuntimeDeviceImpl` terminal session state and flushing.
- `ClientTerminalBuffer` usage in runtime computer screen output.
- Terminal surface rendering as the runtime computer UI output source.

### Keep and strengthen

- `DisplayNetworkBridge`.
- Display attach/resize/detach messages.
- `FrameDeltaClientMessage`.
- `ClientDisplayBuffer`.
- `DisplayRegistry` and frame delta generation.
- Existing input event packets, as discrete event transport.

### Staged VM-side removal

The final state should remove VM-side stdout/terminal concepts as runtime UI primitives. To reduce risk, cleanup should be staged:

1. First remove the client-server terminal/stdout path and switch the runtime UI to display-only.
2. Then remove or replace internal `ComputerStdioBroadcaster`, `VmTerminalApi`, `DeviceStdioApi`, `ScreenBuffer`, and snapshot dependencies after all visible diagnostics are available through display or structured logs.
3. Finally update docs and tests so stdout/terminal APIs are no longer described as the runtime UI model.

## ROM terminal rendering

The ROM terminal should not use `stdout::write` for visible user interaction.

It should maintain:

- confirmed shell output lines;
- current editable input line;
- cursor/prompt state;
- display dimensions.

Interactive edits should use a dirty-line renderer:

- typed characters update only the current input line region;
- Backspace redraws only the affected part of the current input line;
- paste updates changed line regions;
- shell output appends lines and marks changed rows;
- resize marks the full screen dirty;
- attach renders the current state once.

This avoids full framebuffer redraw per keypress.

## Workbench behavior

Workbench attach-terminal behavior is temporarily removed or disabled.

Required behavior:

- no live stdout terminal attachment over the network;
- no runtime `stdout_bytes` dependency;
- IDE, file sync, compile/run controls remain available;
- the UI should either hide attach-terminal controls or present a disabled state with a clear message.

A future feature can add a workbench display viewer by attaching to display sessions, not stdout.

## Migration notes

Existing computers may contain old copied ROM support scripts. The previous automatic refresh of bundled ROM support scripts was reverted. Therefore this design must not assume existing workspaces automatically receive the new `terminal.ck`.

Implementation options must be explicit:

- test with newly-created computers; or
- provide manual migration guidance; or
- introduce a separate, reviewed ROM support-script migration mechanism.

This migration decision is separate from removing stdout transport.

## Testing strategy

### No stdout network path

- Verify runtime server ticks no longer call stdout byte sending.
- Verify stdout byte network message registration is removed or unreachable.
- Verify computer UI does not read from `ClientTerminalBuffer`.

### Display-only shell

- Boot a computer through BIOS and ROM terminal.
- Assert shell greeting/prompt is visible via display frames.
- Type `help`, press Enter, and assert shell output appears through display frames.
- Verify Backspace edits the displayed current line correctly.

### Performance guard

- Type multiple characters without Enter.
- Assert the renderer does not emit a full-frame redraw per keypress.
- Prefer assertions on dirty regions/frame deltas rather than wall-clock timing.

### Workbench

- Verify attach-terminal packet/UI path is removed, hidden, or disabled.
- Verify non-terminal workbench functionality remains intact.

### VM cleanup

- Add tests as each VM-side terminal/stdout primitive is removed.
- Ensure startup errors and child process failures remain visible through display or structured diagnostics.

## Risks

- Removing `stdout` too early can hide BIOS/runtime diagnostics.
- Existing copied ROM scripts can keep using old stdout behavior.
- Dirty-line rendering can be more complex than full redraw and needs focused tests.
- Workbench users temporarily lose live terminal attachment until a display viewer exists.

## Open follow-up decisions

- Whether to add a separate ROM support-script migration mechanism.
- Whether workbench display viewing should attach to the same display endpoint as the computer screen or use a separate observer session.
- How structured diagnostics should be exposed once VM-side stdout/terminal APIs are removed.
