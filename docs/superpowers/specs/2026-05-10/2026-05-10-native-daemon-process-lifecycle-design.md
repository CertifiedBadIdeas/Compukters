# Native Daemon Process Lifecycle Design

## Goal

Move CKL process lifecycle operations for the native device daemon into Rust-owned device state so boot, terminal, shell,
and child commands no longer bounce through generic Kotlin host calls for every `process.*` operation.

This is the next step after the device quota/process scheduler groundwork. Kotlin should still own Minecraft
integration, source loading, and compilation, but Rust should own the running process table and scheduling decisions.

## Motivation

The daemon display path now wakes and drains frames without the old per-tick JVM polling loop. Profiling shows the daemon
can start, but terminal workloads still stop at process boundaries because the daemon turns `process.argument`,
`process.run`, and `process.spawn` into generic host requests.

The bundled boot chain depends on process calls:

- `bios.ck` calls `process::run("boot.ck", stdioDescriptor)`;
- `boot.ck` calls `process::run("terminal.ck", process::argument())`;
- `terminal.ck` calls `process::spawn("shell.ck", stdioDescriptor)`;
- `shell.ck` calls `process::run(command + ".ck", encodedArgument)`.

Until those calls are daemon-native, the Rust scheduler cannot keep parent and child CKL programs moving as one device.

## Scope

Included:

- Store process argument and working directory on each native image handle.
- Fast-path daemon-safe process metadata:
  - `process.argument`
  - `process.currentDirectory`
  - `process.changeDirectory`
- Convert `process.spawn` and `process.run` into daemon compile requests instead of generic host calls.
- Keep `ipc.read` daemon-native because shell/stdin waits move into Rust once child processes are daemon-owned.
- Keep source loading and compilation in Kotlin.
- Add a compile-completion JNI bridge so Kotlin can return a compiled CKIM image to the Rust daemon.
- Register child processes, attach child image handles, and schedule children in the Rust daemon process table.
- Make `process.run` park the parent until the child exits, then resume it with the child's exit code.
- Keep `process.wait` on the existing native process wait path.

Excluded:

- Do not move the CKL compiler to Rust in this slice.
- Do not move Workbench/editor source ownership into Rust.
- Do not replace CKL userland process APIs.
- Do not implement OS-like permissions, signals, pipes, or process groups yet.

## Ownership Boundary

Owned by Rust daemon:

- process ids and parent ids;
- process state and runnable/waiting/exited transitions;
- per-process image handle;
- per-process argument;
- per-process working directory;
- `spawn/run/wait` scheduling semantics after an image is compiled;
- parent wake-up on child exit.

Owned by Kotlin:

- resolving program source from ROM/workspace;
- compiling CKL source into CKIM bytes;
- reporting compile/load failures;
- Minecraft tick/quota calls;
- Workbench and client integration.

## Runtime Model

```text
CKL process calls process::spawn/run
  -> Rust daemon creates child pid and emits CompileProgram request
  -> Kotlin loads source and compiles CKIM
  -> Kotlin completes daemon compile request
  -> Rust daemon attaches child image to process table
  -> Rust scheduler runs parent/child according to device quota
```

For `spawn`, the parent resumes with the child pid after the child is registered.

For `run`, the parent becomes a waiter for the child pid. When the child exits, Rust resumes the parent with the child
exit code.

For stdio, `terminal.ck` writes submitted lines with `ipc.write(input, line + "\n")`, while `shell.ck` calls
`stdio.readLine(ctx)`, which uses `ipc.read(ctx.input)`. Since both terminal and shell are native daemon processes,
`ipc.read` must park the shell as a native IPC waiter and wake it from the Rust IPC registry when terminal writes stdin.

## Failure Semantics

If Kotlin cannot load or compile a child program:

- `spawn` should still return a child pid when practical, then complete that child with exit code `1`, matching the
  current Kotlin process manager behavior where a spawned process can fail after creation.
- `run` should resume the parent with exit code `1`.
- Later work can route compiler diagnostics to the process stderr channel through daemon-native IPC.

## Acceptance Criteria

- `process.argument` and `process.currentDirectory` do not appear as daemon generic host requests.
- `process.spawn` and `process.run` emit typed `compileProgram` daemon requests, not generic `hostCall` requests.
- A completed compile request registers and schedules a real child process in Rust.
- `process.run` returns the child exit code after Rust observes child completion.
- `ipc.read` blocks as native scheduler state, not as a generic host call.
- Boot, terminal, and shell can run inside the daemon without Kotlin-owned child process coroutines.
- Existing non-daemon native and interpreter paths keep their Kotlin fallback behavior.
