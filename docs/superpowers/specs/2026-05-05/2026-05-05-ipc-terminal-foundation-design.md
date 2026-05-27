# IPC Terminal Foundation Design

## Context

Phase 1A added a VM-owned framebuffer display path: client endpoints attach with their own resolution, the VM publishes `DisplayFrameDelta` tiles, and the client applies them to `ClientDisplayBuffer`. The next step is to move terminal and shell behavior into the computer instead of relying on host terminal semantics.

The user explicitly does not want `Runtime` to provide high-level `stdin`, `stdout`, or `stderr`. The runtime boundary must stay low-level. Unix-like process I/O should be expressed as an in-ROM/CKL convention built on generic IPC primitives, not as runtime-owned stdio.

## Goals

- Add low-level generic IPC channels inside a single device VM.
- Add asynchronous process spawning so a terminal renderer program can run concurrently with a shell process.
- Expose raw VM event payloads to CKL so terminal programs can read key codes, typed characters, paste text, and later mouse coordinates.
- Keep `Runtime` unaware of TTY, prompt, cursor, scrollback, line editing, ANSI/VT semantics, and stdio names.
- Move ROM shell/command output away from legacy `terminal::*` calls and toward CKL libraries layered on IPC channels.
- Make `terminal.ck` the owner of terminal rendering: it reads display/input events, reads command output from IPC, and draws into `display::*`.

## Non-goals

- Do not add host/client terminal fallback behavior.
- Do not make `stdin`, `stdout`, or `stderr` runtime concepts.
- Do not implement full Unix job control, process groups, pipes, redirection, or PTY naming in this phase.
- Do not add on-screen texture renderer work beyond the existing `ClientDisplayBuffer` delivery path.
- Do not add bytecode `Instruction` variants unless an implementation blocker proves the existing host-call signal path is insufficient.

## Runtime boundary

Runtime provides only kernel-like primitives:

- generic IPC byte/text channels;
- process lifecycle primitives (`spawn`, `wait`);
- existing display framebuffer operations;
- raw VM events with payload access;
- existing filesystem and process loading.

Runtime does not know which channel means `stdin`, `stdout`, or `stderr`. ROM code can define a convention such as `in=<id> out=<id> err=<id>` and pass it through process arguments. This keeps stdio as CKL policy rather than a runtime feature.

## IPC primitive model

Add an ambient `ipc` builtin module with an initial small API:

- `ipc::open() -> Int` creates a channel id.
- `ipc::write(channelId: Int, text: String) -> Unit` appends text to the channel.
- `ipc::read(channelId: Int) -> String` blocks the current CKL process until text is available or the channel is closed.
- `ipc::tryRead(channelId: Int) -> String` returns available text or an empty string without blocking.
- `ipc::close(channelId: Int) -> Unit` closes the channel.

Channels are scoped to one `BackgroundDeviceVm`. They are not network sockets and are not persistent files. The implementation should enforce bounded buffering using existing VM resource limits or a new explicit IPC quota so a child process cannot accumulate unbounded output.

The first version uses text strings because CKL currently has no byte array user type. If a byte array type is added later, IPC can gain byte-oriented operations without changing the high-level architecture.

## Process model

Add asynchronous process APIs:

- `process::spawn(path: String, argument: String) -> Int` starts a child CKL program and returns a pid immediately.
- `process::wait(pid: Int) -> Int` waits for the child and returns its exit code.
- Existing `process::run(path, argument)` becomes a compatibility helper implemented as `spawn` followed by `wait`.

`spawn` should not create an operating-system thread. It should create a child coroutine/task owned by the same `BackgroundDeviceVm`, sharing the same display registry, IPC registry, filesystem API, event manager, and resource limits.

The child receives only the argument string. Any stdio-like meaning is encoded by ROM conventions in that string, not by Runtime.

## Event payload model

The current CKL `Event` type exposes only `name`, which is not enough for a CKL terminal program. This phase must extend the event model with low-level payload access while keeping interpretation outside Runtime.

Minimum shape:

- `Event.name: String` remains available.
- CKL can read positional arguments as primitive values, for example with helper functions such as `events::argInt(event, index)` and `events::argString(event, index)`, or equivalent fields if the language model supports them cleanly.
- Runtime still does not interpret keybindings, line editing, paste handling, or terminal control sequences. It only exposes the raw payload already delivered by server input events.

The implementation plan should choose the smallest event-payload API that fits existing CKL type-system constraints.

## ROM/CKL terminal stack

Add or rewrite ROM files around the IPC convention:

- `stdio.ck`
  - Parses an argument convention such as `in=<id> out=<id> err=<id>`.
  - Provides CKL helper functions like `readLine(ctx)`, `write(ctx, text)`, and `println(ctx, text)`.
  - Is a library, not a runtime feature.

- `terminal.ck`
  - Waits for `display_attach` / `display_resize` when no display is present.
  - Creates IPC channels for shell input/output/error.
  - Starts `shell.ck` via `process::spawn` with channel ids in the argument string.
  - Pulls raw input events (`key`, `paste`, later mouse), converts them into text/control bytes, and writes them to the shell input channel.
  - Polls or reads the shell output channel and renders glyphs, cursor, wrapping, and scrollback into `display::*`.

- `shell.ck`
  - Stops using `terminal::readln`, `terminal::write`, and `terminal::println`.
  - Uses `stdio.ck` to read lines and print prompt/output.
  - Launches command programs with the same channel ids in their argument string.

- ROM command programs (`ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck`, later `nano.ck`)
  - Stop using `terminal::*`.
  - Use `stdio.ck` for command output and errors.

`boot.ck` should run `terminal.ck` instead of `shell.ck`. `bios.ck` remains firmware/bootstrap code and should not own terminal UI after user boot starts.

## Multiple terminals

The first implementation may run one terminal instance, but the design must not bake in a singleton TTY. Multiple terminal instances are modeled as multiple `terminal.ck` processes, each with its own channel set and display endpoint. Runtime only sees independent channels and processes.

## Testing strategy

- Compiler tests verify the `ipc` builtins and `process::spawn` / `process::wait` types are visible in the runtime registry.
- Compiler/runtime tests verify CKL can read event payloads needed by terminal input.
- Core IPC tests cover open/write/read/tryRead/close and bounded buffering.
- Core process tests prove `spawn` returns without waiting and `wait` returns the child exit code.
- VM integration tests prove a parent can spawn a child, continue executing, and receive child output through IPC.
- ROM compile tests ensure `bios.ck`, `boot.ck`, `terminal.ck`, `shell.ck`, and commands compile without legacy `terminal::*` usage.
- Display integration tests prove terminal output reaches `ClientDisplayBuffer` through the existing frame path.

## Risks and constraints

- `spawn` changes VM scheduling more than Phase 1A did; implementation must proceed in small TDD commits.
- IPC buffering must be bounded from the start.
- Blocking `ipc::read` and `process::wait` must suspend only the current CKL task, not the whole VM.
- Child process failures should surface as non-zero exit codes and diagnostic output through the ROM stdio convention where possible.
