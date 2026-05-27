# VM framebuffer display architecture

Date: 2026-05-05

## Problem

The current computer UI is terminal-first. Runtime output is still modeled around terminal bytes, terminal buffers, and
terminal-specific client widgets. This conflicts with the desired direction: the computer should render its own image in
the VM, while the Minecraft client should only keep a presentation layer with double buffering and minimal data transfer.

The mod is still in alpha, so this design does not preserve the old terminal path as a compatibility fallback. The old
terminal implementation may be removed or bypassed when it blocks the new display-first model.

## Goals

- Replace terminal-first output with a display-first framebuffer contract.
- Let the VM render the final image for a display endpoint.
- Keep the client presentation layer semantic-free: apply frame deltas, swap buffers, show pixels.
- Let the client or world display endpoint define the resolution, not the VM profile.
- Keep the model compatible with future multi-block displays.
- Make terminal and shell ordinary in-VM programs built on display and input APIs.
- Leave room for a future IDE session protocol that connects to a live device instead of running the device inside IDE.

## Non-goals

- No backwards-compatible fallback for the old terminal screen or `ScreenBufferSnapshot` path.
- No full terminal shell implementation in this first phase.
- No IDE agent implementation in this first phase.
- No multi-block monitor implementation in this first phase.
- No full GPU-like command API. The transport contract is ready-made pixels, not client-side draw commands.

## Target model

A Runtime Device no longer owns a fixed terminal resolution as part of its VM profile. A display endpoint owns the
resolution and attaches to the runtime device. An endpoint can be a player GUI, a terminal item screen, a monitor block,
or a future multi-block display.

On attach or resize, the endpoint announces:

- `displayId`;
- width and height in logical pixels;
- pixel format;
- optional transport limits such as max frame rate or max payload size.

The server validates the endpoint and delivers the change to the VM as display events such as `display_attach` and
`display_resize`. The VM program decides how to react: redraw layout, letterbox, show an unsupported-size message, or
ignore the display.

The VM mutates a back buffer for a display and then presents it. Presenting produces a versioned frame delta. The server
delivers that delta to the endpoint. The client does not know whether the pixels represent a terminal, shell, boot
screen, file manager, or IDE bridge UI.

## Components

### Display endpoint

`DisplayEndpoint` is the server-side model of an attached display target. It owns the display resolution and routes
frames to the concrete output surface.

First phase endpoint:

- one GUI endpoint opened by a player;
- client-derived width and height;
- one active display per opened GUI is enough for the first implementation.

Future endpoints:

- terminal item screen;
- single monitor block;
- multi-block monitor structure;
- remote or networked display.

For a multi-block display, the endpoint can expose one logical resolution to the VM and split the resulting texture over
blocks on the Minecraft rendering side. This does not require a different VM API.

### Device display API

The runtime exposes a low-level display API to CKL programs. The first version should be intentionally small:

- list or query attached displays;
- get display size and pixel format;
- clear or fill the back buffer;
- write pixel spans or rectangles;
- optionally blit from VM-owned image memory;
- present the back buffer.

Higher-level UI, terminal rendering, text drawing, shell, and boot UI live in CKL libraries or firmware code above this
API.

### Framebuffer state

The VM host keeps per-display framebuffer state:

- back buffer mutated by the VM;
- front or last-presented state used to compute deltas;
- sequence number;
- dirty tracking data.

Dirty tracking should be tile-based. A tile size such as 8x8 or 16x16 pixels keeps diffing simple and avoids sending full
frames for small changes. Rect-based framing can still be derived from tiles later if needed.

### Frame transport

Server-to-client frame messages carry:

- `displayId`;
- `sequence`;
- width and height;
- pixel format;
- dirty tile or dirty rect metadata;
- encoded pixel payload.

If the client detects a missing sequence, format mismatch, or size mismatch, it asks for a full refresh. Full refresh is a
normal recovery path, not a fatal error.

### Client double buffering

The client keeps two buffers or textures for the endpoint:

- visible front buffer;
- staging back buffer.

Incoming frame deltas are applied to staging. On the render tick, the client swaps staging into the visible buffer. The
client does not run terminal logic, VT parsing, text shaping, shell state, or application rendering.

## Data flow

1. A client or world display opens and announces endpoint parameters to the server.
2. The server validates the endpoint and sends `display_attach` or `display_resize` to the VM event queue.
3. The VM program redraws into the endpoint back buffer.
4. The VM calls `present` for the endpoint.
5. The host computes dirty tiles and creates a frame delta with a new sequence number.
6. The server sends at most the latest relevant frame delta per endpoint per tick.
7. The client applies the delta to staging and swaps buffers on render.

## Input model

Input is endpoint-first, not terminal-first. Client or world input targets a display/input session and reaches the VM as
neutral events:

- key down and key up;
- typed character;
- mouse move, click, release, drag, and scroll;
- paste;
- focus and blur;
- display resize.

Each input event includes enough identity to let the VM route it, usually `displayId` and optional session/user metadata.
The server validates distance, chunk state, ownership, and current endpoint validity before enqueuing input.

## Terminal as an in-VM program

Terminal is no longer a native Minecraft UI concept. It becomes a CKL program or standard library running inside the
device. It reads endpoint input events, maintains shell state, and renders terminal pixels into the display framebuffer.

Responsibilities of the terminal program:

- input line editing;
- command history;
- cursor and selection state;
- scrollback;
- shell command execution;
- rendering text and UI chrome into pixels.

This makes boot screens, shells, file managers, and debug UIs use the same display/input substrate.

## Future IDE session model

The future IDE should not run the computer inside the IDE. It should connect to a live Runtime Device. A program or
firmware service on the device, for example `ide_agent.ck`, provides an IDE protocol:

- workspace metadata;
- file read and write operations;
- diagnostics and capabilities;
- push, pull, run, and debug commands;
- connection status.

The Workbench or IDE client talks to that agent. If the device is off, unreachable, or the agent is not running, IDE shows
a connection state instead of creating a local replacement runtime.

This first framebuffer phase does not implement the IDE agent, but all display and input identity should stay neutral so
the agent can be added later without coupling to terminal internals.

## Error handling

- Unsupported endpoint parameters are rejected on attach or reported as display errors to the VM.
- Missing frame sequences trigger full refresh.
- Resize invalidates old frame state and starts a new sequence stream.
- Excessively large payloads may be rate-limited or converted into a full refresh at a lower frame rate.
- If the endpoint disappears, the server sends a detach event to the VM and stops sending frames.
- VM errors are device/runtime errors; they should not be encoded as terminal UI state.

## Performance constraints

- Prefer a compact first pixel format, such as palette indices or `RGB565`, before `RGBA8888`.
- Use tile dirty tracking.
- Limit present/frame publication to the runtime budget and server tick rate.
- Send at most the latest relevant frame per endpoint per tick.
- Avoid client-side semantic rebuilds. The client applies bytes to buffers only.

## First implementation scope

The first phase should deliver:

1. Display endpoint and framebuffer data models.
2. Runtime display API surface.
3. Client-defined GUI endpoint resolution.
4. VM display attach/resize events.
5. Frame delta serialization and recovery through full refresh.
6. Client double-buffer apply/swap model.
7. Minimal CKL or firmware demo that draws pixels and reacts to input.
8. Removal or bypass of old terminal-specific UI paths where they conflict with the new architecture.

The first phase should not deliver:

- full terminal shell;
- full IDE agent;
- multi-block display rendering;
- complex sprite, font, or GPU command abstractions.

## Testing strategy

- Unit tests for framebuffer mutation and dirty tile calculation.
- Unit tests for frame sequence handling and full-refresh recovery.
- Serialization tests for frame delta messages.
- Runtime tests for display attach, resize, and present events.
- Input routing tests for neutral key, char, mouse, paste, and resize events.
- Client model tests for applying frame deltas to staging and swapping buffers, if the current client architecture allows
  isolated testing.
- Smoke test: an active device receives client-defined resolution, renders a frame in the VM, and the client presents it
  without terminal-specific rendering logic.

## Acceptance criteria

- Display resolution comes from the endpoint/client, not from the VM profile.
- The VM renders complete image pixels for the endpoint.
- The client only applies frame data and swaps buffers.
- Terminal semantics are no longer required in the client display layer.
- The design can represent a future multi-block display as one logical endpoint.
- The design can later host terminal-as-program and IDE-agent workflows without changing the display transport model.