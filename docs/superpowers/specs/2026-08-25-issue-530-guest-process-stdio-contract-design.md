# Guest Process, Arguments, Exit Status, and Standard I/O Design

> Issue: [#530](https://github.com/CertifiedBadIdeas/Compukters/issues/530)

## Context

Compukters already boots an extensionless `/rom/boot` artifact, lets boot run
`/rom/shell`, and lets shell synchronously wait for one foreground child. Rust
owns the process-frame stack, terminal, and VFS. The active child has an
independent `Session`, heap, frames, verifier state, and pending requests while
the parent remains suspended.

The current guest contract is intentionally transitional. Shell passes one raw
command-line string, child programs retrieve it through
`Process.commandLine()`, normal completion always produces integer status `0`,
runtime and admission failures occupy the same raw integer result space, and
callers pass an unexplained numeric capability mask. Programs that want console
I/O call the low-level character terminal directly. Trusted blocking host calls
must currently be declared as Kotlin `suspend` functions even though the VM,
not a Minecraft or OS thread, performs the wait.

This design replaces that transitional surface with a normal Guest Kotlin
process model. It preserves the existing single foreground lane and bounded
Rust ownership while leaving task-local suspension compatible with later guest
concurrency.

## Goals

- Support ordinary Kotlin entry points with structured bounded arguments.
- Preserve Kotlin `Unit` entry points while adding explicit guest exit codes.
- Distinguish guest-selected exit status from admission, trap, quota, VM, host,
  and I/O failures through a typed public result.
- Provide inherited `stdin`, `stdout`, and `stderr` endpoints for computer
  programs without conflating standard I/O with the raw character terminal.
- Let VM-blocking operations look synchronous in Guest Kotlin while parking
  only the calling execution task.
- Remove public capability masks and per-process authority delegation. Versioned
  host interfaces describe available services and artifact imports, not user
  permissions.
- Keep every input, diagnostic, allocation, process transition, and stream
  buffer explicitly bounded.

## Non-Goals

- Background processes, multiple foreground lanes, job control, signals,
  cancellation, pipes, redirection, or general file descriptors.
- Environment variables, a current-working-directory model, shell expansion,
  globbing, command substitution, or source-path execution.
- Per-process security policies or an untrusted-program sandbox.
- Project manifests, addon SDK selection, controller BSP/HAL selection,
  peripheral discovery, or deployment-time hardware binding.
- Standard streams on every future device. Controllers may provide no standard
  streams and may instead expose explicit UART, serial, or other hardware APIs.
- Backward execution compatibility with the transitional `process@1` ABI.

## Terminology and Module Boundary

Device-independent Guest Kotlin standard-library code and platform I/O are
separate layers:

```text
Guest Kotlin platform
├── builtins and device-independent stdlib
│   └── Unit, Int, String, Array, collections, text, math
├── optional Compukter stdio module
│   └── print, println, readln, stdin, stdout, stderr
├── Compukter platform modules
│   └── Process, FileSystem, Terminal, Compiler
└── private trusted host bindings
    └── versioned VM operations used by the public wrappers
```

The first implementation compiles user programs against an implicit computer
module template containing Kotlin core plus Compukter stdio, process, and
filesystem modules. A later project-manifest design will select modules for
controllers and addons explicitly. This design does not imply that `println`
exists on a controller firmware target without a configured standard-output
endpoint.

`Terminal` is a low-level character-grid and raw-event platform API. It is not
the implementation identity of standard I/O, even when all three standard
streams happen to be backed by the same terminal device.

## Public Guest Kotlin API

The process module exposes the following conceptual API:

```kotlin
package compukter.process

object Process {
    fun run(
        path: String,
        args: Array<String> = emptyArray(),
    ): ProcessResult

    fun exit(code: Int): Nothing
}

sealed interface ProcessResult {
    data class Exited(val code: Int) : ProcessResult

    data class Failed(
        val reason: ProcessFailureReason,
        val diagnostic: String,
    ) : ProcessResult
}

enum class ProcessFailureReason {
    INVALID_PATH,
    NOT_FOUND,
    ACCESS_DENIED,
    NOT_EXECUTABLE,
    INVALID_PROGRAM,
    INCOMPATIBLE,
    LIMIT_EXCEEDED,
    TRAPPED,
    VM_FAULT,
    HOST_FAILURE,
    IO_FAILURE,
}
```

The stdio module supplies ordinary console functions for the computer template:

```kotlin
fun print(value: String)
fun println()
fun println(value: String)
fun readln(): String
```

The first implementation also supplies `print` and `println` overloads for
`Int`, `Boolean`, and `Char` using the canonical Guest Kotlin string
conversion. General `Any?` formatting depends on the broader object/string
standard-library design and is not required by this issue.

Explicit diagnostic output is available as
`compukter.io.Stderr.write(String)`. It belongs to the optional stdio module and
must not be placed on `Terminal`.

## Entry-Point Contract

One artifact accepts exactly one of these entry points:

```kotlin
fun main()
fun main(args: Array<String>)
suspend fun main()
suspend fun main(args: Array<String>)
```

Ordinary non-suspending `main` is the default expected form. Existing Kotlin
support for `suspend main` remains valid, but no program must declare `main` as
`suspend` merely to call `readln`, `Process.run`, or `Terminal.awaitEvent`.

If compilation finds zero supported entry points, or more than one supported
entry point across any files in the project, compilation fails with a source
diagnostic. The compiler never silently chooses between `main()` and
`main(Array<String>)`.

All four source forms lower to one verified internal entry contract. Rust does
not discover Kotlin declarations. For the no-argument form the child still
owns the validated argument payload but does not materialize an unused guest
array. For the argument form Rust materializes an independent `Array<String>`
and its strings in the child heap before entry begins.

## Structured Arguments

`Process.run` receives an array that excludes the executable name, matching the
Kotlin/JVM `main(args)` convention. The process path is a canonical absolute
virtual path. Shell alone resolves a bare command name first to `/home/<name>`
and, after a not-found result, to `/rom/<name>`.

The shell parses one deliberately small POSIX-like grammar:

- unquoted whitespace separates arguments;
- single quotes preserve their contents literally;
- double quotes preserve whitespace and permit backslash escaping;
- outside single quotes, backslash escapes the following code unit;
- adjacent quoted and unquoted segments form one argument;
- empty quotes create an empty argument;
- an unterminated quote or trailing escape is a shell syntax error and does
  not start a process;
- variable expansion, globbing, command substitution, pipes, and redirection
  are absent.

For example:

```text
greet Ada "Red Engineer" '' pre"fix value"post
```

produces:

```kotlin
arrayOf("Ada", "Red Engineer", "", "prefix valuepost")
```

Programmatic arguments may contain arbitrary Guest Kotlin UTF-16 code units,
including NUL. The private transport is length-delimited and preserves every
code unit; it never reserves a Guest Kotlin character as a separator. Shell
input remains narrower because its line editor rejects unsupported control
characters before lexing.

The execution profile bounds argument count, the code-unit length of one
argument, and total argument code units. Guest heap accounting additionally
charges the array and every materialized string. Bounds are checked before the
child becomes visible. This design deliberately does not assign production
numbers: device profiles and later benchmarks own those values.

## Exit and Failure Semantics

Returning normally from any supported `main` produces
`ProcessResult.Exited(0)`. `Process.exit(code)` immediately terminates only the
calling guest process with `Exited(code)`. Accepted guest exit codes are
`0..255`. A dynamic code outside that range terminates as `TRAPPED` with a
bounded diagnostic rather than clamping or wrapping.

`Process.exit` is not a Kotlin exception and does not unwind guest `finally`
blocks. Rust always releases runtime-owned process resources, reservations,
pending input ownership, and request state when the frame is removed.

Stable public failure reasons intentionally group more detailed Rust outcomes:

| Public reason | Meaning |
| --- | --- |
| `INVALID_PATH` | The supplied virtual path is not canonical or violates VFS limits. |
| `NOT_FOUND` | No executable file exists at the path. |
| `ACCESS_DENIED` | Machine/VFS policy forbids executing the path. |
| `NOT_EXECUTABLE` | The path is not a regular executable file. |
| `INVALID_PROGRAM` | Artifact decoding or structural/semantic verification failed. |
| `INCOMPATIBLE` | Entry ABI, VM ABI, or required versioned host interface is unavailable. |
| `LIMIT_EXCEEDED` | Process depth, start count, admission memory, argv, or another declared process bound was exceeded. |
| `TRAPPED` | Guest-defined execution trapped, including an invalid explicit exit code. |
| `VM_FAULT` | The VM encountered an internal execution fault rather than a guest trap. |
| `HOST_FAILURE` | A required bounded host operation failed outside its normal domain result. |
| `IO_FAILURE` | Loading or stream/storage I/O failed. |

Every failure carries a scalar-safe bounded human-readable diagnostic. The
reason is stable and suitable for program logic; diagnostic wording is for
people and may gain source positions or stack information later. Internal Rust
statuses may remain more precise and may change without expanding the public
enum.

Rust does not automatically print the diagnostic. `Process.run` returns it to
the parent. The shell writes a non-success exit or failure through `stderr`, so
one failure is not printed twice and another parent remains free to handle it
programmatically.

## Standard-Stream Model

`ComputerMachine` owns three logical stream endpoints independently of process
frames:

```text
ComputerMachine
├── TerminalDevice
├── StandardStreams
│   ├── stdin  -> canonical terminal input
│   ├── stdout -> terminal character output
│   └── stderr -> terminal character output
├── VFS
└── foreground process stack
```

Boot receives the computer's standard endpoints. Each foreground child
inherits the same endpoint references. Because the current scheduler runs only
the top process frame, the suspended parent cannot consume input while the
child owns the foreground lane. Stream objects and terminal state are not
copied into each guest heap.

Terminal-backed stdin uses Rust-owned canonical line discipline:

- text/key events append bounded input;
- Backspace removes the previous Unicode scalar;
- Enter commits one line;
- committed input is normalized to LF semantics;
- `readln()` returns the line without a terminator;
- accepted input and editing are echoed only for a terminal-backed endpoint;
- future non-terminal endpoints such as files, pipes, or UART adapters do not
  gain terminal echo automatically.

The first stream implementation has no ordinary terminal EOF. Shutdown or
reboot tears down the process rather than returning a fabricated line. A future
pipe/file design may add `readlnOrNull` with explicit EOF semantics.

`stdout` and `stderr` are logically distinct but initially render through the
same terminal device. Runtime ordering follows accepted operation order. The
runtime does not color stderr implicitly because TUI state and presentation
remain under guest control.

Raw input through `Terminal.awaitEvent` bypasses canonical stdin. A process may
hold at most one active input wait mode at a time. Attempting simultaneous raw
and canonical ownership is rejected deterministically. Sequential switching is
allowed after the prior event or line has been completed.

## VM-Blocking Calls and Future Tasks

The public signatures of `Process.run`, `readln`, and `Terminal.awaitEvent` are
ordinary functions. K2 recognizes only their trusted SDK identities and lowers
them to VM-blocking host operations. A player-defined function with the same
package, name, or signature remains ordinary guest code.

A VM-blocking operation:

1. records the destination and continuation of the calling execution task;
2. marks that task waiting;
3. returns control to the Rust scheduler and then Minecraft without blocking
   the server thread;
4. makes the task runnable again when the bounded response arrives;
5. resumes at the exact continuation with the response value.

The first implementation has one execution task per process, so observable
behavior remains single-tasked. Pending waits must nevertheless be owned by a
task identity rather than described as whole-VM blocking. A later guest
scheduler may run another task while one waits for input, a timer, a network
event, or a child process without changing these public APIs.

Multiple future tasks reading one stdin or raw terminal event queue compete for
events; input is not implicitly broadcast. Subscription/broadcast semantics
require a separate API.

## Host Interfaces, Not Process Permissions

The runtime retains versioned host-interface identities such as:

```text
compukter/process@2
compukter/stdio@1
compukter/terminal@2
compukter/filesystem@1
compukter/compiler@1
```

They serve two purposes: an artifact declares the services it imports, and a
machine supplies the services its hardware/runtime supports. They are not
user-visible permission bits.

For a child launch, Rust resolves the artifact's declared interfaces directly
against the machine bindings. Every process on one computer belongs to the same
guest trust domain. The transitional `ProcessCapabilityMask`, numeric guest
argument, and parent-to-child authority delegation disappear from the public
and production process path.

Safety remains enforced at the actual guest/host boundary: verified artifacts,
bounded host operations, Rust-owned VFS path validation and quotas, immutable
ROM, no ambient host filesystem or network access, and explicitly registered
addon adapters. A guest program may damage its own writable `/home`; that is
ordinary computer behavior. Running untrusted code with narrowed authority is
a separate future sandbox feature.

## Runtime Data Flow

One successful foreground launch follows this transaction:

1. Shell lexes the line and resolves an executable path.
2. Public `Process.run(path, args)` enters the trusted private process binding.
3. Rust validates path and argv bounds.
4. Rust reads the extensionless executable through the machine VFS.
5. The artifact decoder and verifier validate the complete artifact.
6. Admission resolves declared host interfaces against the machine.
7. Aggregate process depth, starts, heap, frame storage, and argument
   reservations are checked with overflow-safe arithmetic.
8. Rust creates the child `Session`, materializes argv if required, and starts
   the verified entry point.
9. Only after all prior steps succeed does Rust mark the parent task waiting and
   publish the child frame as the active stack top.
10. The child inherits standard endpoint references and runs alone in the
    foreground lane.
11. Normal return, explicit exit, trap, or failure removes the complete child
    frame and releases reservations.
12. Rust atomically stores the bounded completion for the exact waiting parent
    task and resumes the private binding with one compact raw status.
13. The trusted Guest Kotlin wrapper maps a non-negative raw status to
    `Exited`, or consumes the same task's bounded diagnostic and maps a negative
    status to `Failed`.

Any failure before publication leaves no child frame, pending parent wait, or
leaked reservation. Raw non-negative statuses encode exit codes `0..255`;
private negative statuses encode the stable failure reasons. Failure diagnostic
state belongs to the waiting task, is consumed exactly once by the trusted
wrapper, and is never exposed as a machine-global "last error". The raw status
and retrieval operation are private SDK bindings rather than public Guest
Kotlin API.

## ABI and Migration

The accepted contract is a clean `compukter/process@2` ABI and a new
`compukter/stdio@1` ABI. It removes raw command-line retrieval, raw capability
masks, and Kotlin-suspending public process calls. ROM programs and trusted SDK
sources migrate together.

No compatibility adapter or dual process runtime is retained:

- boot, shell, kotlinc, and edit are regenerated with the current mod;
- compiler cache keys include SDK and ABI versions and therefore invalidate;
- a persisted old `/home` artifact fails admission as `INCOMPATIBLE`;
- a retained `.kt` source can be rebuilt with the current `/rom/kotlinc`.

The artifact bytecode format changes only where required for object arrays,
entry arguments, typed guest values, or blocking-call metadata. Its format and
VM ABI versions are bumped once for the accepted implementation rather than
supporting old and new variants simultaneously.

## Limits and Accounting

The execution/device profile supplies all production values. This design
requires independently testable bounds for:

- argument count;
- one argument's UTF-16 code units;
- aggregate argument code units;
- canonical stdin line code units;
- stdout/stderr operation payload and queued output;
- failure diagnostic code units;
- process depth and starts;
- aggregate reserved heap and frame storage;
- pending blocking requests and input ownership.

Tests use deliberately small profiles to exercise every boundary. Production
numbers remain outside the stable ABI until device classes and representative
memory/performance benchmarks are defined.

## Implementation Decomposition

Issue #530 is the integration parent. Implementation proceeds as sequential
child issues and commits on the repository's default development branch, `dev`:

1. **[#531 Entry argv](https://github.com/CertifiedBadIdeas/Compukters/issues/531):** object `Array<String>` support, the four entry forms,
   ambiguity diagnostics, and bounded child-heap materialization.
2. **[#532 VM-blocking calls](https://github.com/CertifiedBadIdeas/Compukters/issues/532):** trusted synchronous-looking calls that park a task,
   task-owned continuation state, and removal of Kotlin `suspend` leakage.
3. **[#533 Process completion](https://github.com/CertifiedBadIdeas/Compukters/issues/533):** the Guest Kotlin types required by
   `ProcessResult`, stable failure mapping, bounded diagnostics,
   `Process.exit`, and `process@2`.
4. **[#534 Standard streams](https://github.com/CertifiedBadIdeas/Compukters/issues/534):** `stdio@1`, canonical terminal input, stdout/stderr,
   and the initial print/println/readln surface.
5. **[#535 Shell integration](https://github.com/CertifiedBadIdeas/Compukters/issues/535):** the bounded lexer, structured argv, migrated ROM
   programs, regenerated artifacts, and complete playable-loop coverage.

The executable task sequence is recorded in the
[implementation plan](../plans/2026-08-25-issue-530-guest-process-stdio-contract.md).

Project manifests, controller module templates, addon SDK selection, UART
binding, and peripheral search remain follow-up designs and do not enter these
children.

## Verification

Compiler tests cover:

- all four supported entry forms;
- zero and ambiguous entry diagnostics across multiple source files;
- `Array<String>` construction, indexing, iteration needed by the wrappers,
  and deterministic lowering;
- trusted VM-blocking intrinsic recognition from the SDK bundle only;
- typed process result construction and matching;
- deterministic boot, shell, kotlinc, and edit artifacts.

Rust VM tests cover:

- zero, empty, Unicode, maximum, and excessive arguments;
- allocation/accounting failure before child publication;
- normal return and every explicit exit value;
- invalid explicit exit trapping;
- every stable failure-category mapping and scalar-safe diagnostic bounds;
- task-local pending process/stdio state and exact continuation resume;
- canonical input, Backspace, echo, LF commit, and input bounds;
- stdout/stderr ordering and raw/canonical ownership conflicts;
- cleanup of frames, reservations, pending events, and stream ownership.

Runtime and Minecraft integration tests cover:

- `boot -> shell -> child -> shell` foreground lifecycle;
- quoted, escaped, empty, and malformed shell arguments;
- a normal Kotlin program that prompts, reads a line, prints a response, and
  exits with a selected status;
- shell diagnostics for not found, incompatible, trapped, and limited children;
- compiler cache invalidation and explicit rejection of old process artifacts;
- reboot with a fresh process/task/terminal state and retained `/home` source.

Final verification runs focused compiler, native-runtime, core, and GameTest
coverage, followed by `./gradlew-sandbox-dev-parallel verifyLocalFull
--rerun-tasks` and `git diff --check`.
