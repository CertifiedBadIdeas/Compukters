# VM stdio/terminal removal design

Date: 2026-05-06
Status: Approved design for implementation planning

## Context

The previous display-only runtime I/O branch removed client-server terminal/stdout transport. Runtime computer clients now receive display frame deltas, and ROM `terminal.ck` renders visible interaction through `display::*` instead of `stdout::write`.

A staged compatibility layer remains inside the VM and language:

- `terminal` and `stdout` builtins are still present in `LanguageBuiltins`.
- `RuntimeHostBridge` still dispatches `terminal`/`stdout` calls.
- `DeviceRuntime` still exposes `DeviceTerminalApi` and `DeviceStdioApi`.
- `BackgroundDeviceVm` still owns legacy `ScreenBuffer`/stdio plumbing for terminal compatibility.
- `firmware/bios.ck` still uses `terminal::println` for boot diagnostics.

This follow-up removes those VM-side terminal/stdout concepts. Programs must render visible output themselves through display/framebuffer APIs.

## Decisions

- Do the implementation in a separate worktree.
- Remove VM-side `terminal` and `stdout` APIs rather than keeping them as internal output primitives.
- Do not add an internal VM diagnostics renderer.
- Programs, firmware, and ROM are responsible for rendering their own visible output through `display::*`.
- Use the existing stdio-channel convention for parent/child process communication, but make it explicit and tagged.
- Use tagged stdio descriptors only; no compatibility for the old untagged descriptor format.

## Goals

- Remove `terminal` and `stdout` from the public CKL runtime builtins.
- Remove terminal/stdout dispatch from runtime execution.
- Remove `DeviceTerminalApi`, `DeviceStdioApi`, `VmTerminalApi`, `ComputerStdioBroadcaster`, and VM-owned terminal screen-buffer plumbing when no main-code consumer remains.
- Keep process failures visible to programs by writing child loader/compiler/runtime errors to the child stderr channel when a tagged stdio descriptor is supplied.
- Update bundled firmware/ROM scripts to render boot and shell output through display-driven programs.
- Make old `terminal::`/`stdout::` usage fail as unknown modules.

## Non-goals

- Do not implement a Workbench display viewer in this stage.
- Do not add a new network stdout/stderr transport.
- Do not add `process::run` or `process::spawn` overloads.
- Do not automatically migrate existing user workspace scripts.
- Do not preserve old untagged stdio argument descriptors.
- Do not remove Workbench UI snapshot/terminal types unless they become unused as a direct consequence of VM cleanup.

## Target runtime model

### Visible output

Visible runtime UI is owned by CKL programs. Programs use `display::*` to draw framebuffer state and call `display::present()` to publish it. The VM runtime does not draw diagnostics, prompts, or errors by itself.

### Builtin modules

The default runtime registry keeps device APIs such as `display`, `filesystem`, `system`, `events`, `ipc`, `process`, and `strings`, but removes `terminal` and `stdout`.

CKL code that imports or calls `terminal::*` or `stdout::*` should fail during frontend resolution with an unknown module/name error.

### Process stderr

The process API keeps the current signatures:

- `process::run(path: String): Int`
- `process::run(path: String, argument: String): Int`
- `process::spawn(path: String): Int`
- `process::spawn(path: String, argument: String): Int`
- `process::wait(pid: Int): Int`

Process startup uses a tagged stdio descriptor embedded in `argument`:

```text
stdio-v1 <stdin> <stdout> <stderr> <argument>
```

`stdio.ck` owns descriptor encoding/decoding. Bundled callers use `stdio::encode(ctx, argument)` and callees use `stdio::fromArgument(process::argument())`.

If `VmProcessManager` can decode a `stdio-v1` descriptor from the child argument, it writes its own child-process errors to the decoded `stderr` IPC channel:

- program not found;
- compilation error;
- runtime exception.

If no tagged stdio descriptor exists, `VmProcessManager` logs the error through the server logger and returns a non-zero exit code. It does not write to any global terminal/stdout sink and does not render display output.

### Firmware and ROM

`firmware/bios.ck` must stop using `terminal::println`. It should render boot status and boot failure text through `display::*` directly or through a ROM helper.

For launching `boot.ck`, BIOS opens stdio channels, passes a tagged descriptor to `process::run("boot.ck", ...)`, reads stdout/stderr channels, and renders any text it chooses to show.

`rom/terminal.ck` remains the interactive UI owner. It reads shell stdout/stderr IPC channels and renders them to display frames. `rom/shell.ck` and external ROM programs use `stdio.ck` helpers to write stdout/stderr.

## Component changes

### Compiler/frontend

- Remove `terminal` and `stdout` from `LanguageBuiltins.defaultRuntimeRegistry`.
- Update IDE completion/hover/import tests to no longer expect those modules.
- Rewrite compiler snippets that use `terminal::println` to use pure computations, `display::*`, or other remaining builtins.
- Add tests proving `terminal`/`stdout` imports and calls are rejected.

### Runtime bridge and APIs

- Remove `RuntimeHostBridge` handlers for `terminal` and `stdout`.
- Remove `DeviceRuntime.terminal` and `DeviceRuntime.stdio`.
- Remove `DeviceTerminalApi` and `DeviceStdioApi`.
- Update `VmRuntime` construction and all runtime API call sites.
- Remove `VmTerminalApi`, `ComputerStdioBroadcaster`, `ScreenBufferVtSink`, and cursor/VT support if no main-code references remain.

### Process manager

- Remove terminal API dependency from `VmProcessApi` and `VmProcessManager`.
- Add stdio descriptor decoding in runtime code, preferably shared with the semantics of `stdio.ck` to keep format rules aligned.
- When child load/compile/runtime errors occur, write the message to decoded stderr IPC channel if present.
- Keep cancellation quiet.

### VM handle and runtime device

- Remove VM-owned `ScreenBuffer` from `BackgroundDeviceVm` once terminal APIs no longer need it.
- Remove `readScreenSnapshot()` / `forceScreenSnapshot()` from VM handle if no required main-code caller remains.
- Update `RuntimeDeviceScreen` / Workbench snapshot paths to no-op, disabled, or removed according to actual remaining dependencies.

### Bundled scripts

- Update `firmware/bios.ck` to render with display APIs and tagged stdio descriptors.
- Update `rom/stdio.ck` to emit and parse only `stdio-v1` descriptors.
- Update `rom/shell.ck` and external commands to use the tagged stdio helpers.
- Audit all bundled firmware/ROM scripts for `terminal::` and `stdout::`.

## Testing strategy

### Red tests first

Add tests before implementation for:

- default runtime registry does not contain `terminal` or `stdout`;
- `RuntimeHostBridge` rejects or cannot dispatch `terminal`/`stdout`;
- bundled firmware/ROM sources do not contain `terminal::` or `stdout::`;
- process load/compile/runtime errors go to tagged stderr IPC when supplied;
- `stdio.ck` encodes tagged descriptors and rejects old untagged descriptors;
- IDE completions no longer list `terminal`/`stdout` modules.

### Focused verification

Use focused module tests while changing each layer:

- compiler tests after builtin removal and snippet rewrites;
- core VM tests after process stderr and API deletion;
- NeoForge ROM compile/tests after firmware/ROM changes.

### Final audit

Run a final source audit over main source and bundled ROM/firmware for removed symbols:

- `terminal::`
- `stdout::`
- `DeviceTerminalApi`
- `DeviceStdioApi`
- `VmTerminalApi`
- `ComputerStdioBroadcaster`
- `ScreenBufferVtSink`
- `RuntimeHostBridge` terminal/stdout dispatch functions

Historical docs under `docs/superpowers` may retain old references as design history.

## Compatibility and migration

This is a breaking runtime-language cleanup. Existing user programs that call `terminal::` or `stdout::` will fail to compile after the change. Existing workspaces using the old untagged stdio argument convention will also fail until scripts are updated.

Bundled firmware and ROM scripts must be updated in the same branch. Automatic migration for existing user workspaces is out of scope and should be planned separately if needed.

## Risks

- Boot failures can become invisible if BIOS does not render display messages after terminal removal.
- Process errors can be lost if callers do not pass a tagged stdio descriptor.
- Removing `ScreenBuffer` may expose hidden Workbench snapshot dependencies.
- Compiler and IDE tests have many old `terminal::println` snippets and may require broad rewrites.

## Open follow-up decisions

- Whether to build a Workbench display viewer that observes display sessions.
- Whether to provide a user workspace migration for old stdio descriptors and terminal-based examples.
- Whether future diagnostics need structured machine-readable state in addition to display-rendered text.
