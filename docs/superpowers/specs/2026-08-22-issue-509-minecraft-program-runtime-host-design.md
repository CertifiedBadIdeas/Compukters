# Minecraft Program Runtime Host Design

> Issue: [#509](https://github.com/CertifiedBadIdeas/Compukters/issues/509)

## Context

The standalone playground from issue #506 proves the complete Kotlin source to isolated K2 worker to Artifact v1 to native Rust VM pipeline. The Minecraft-facing `core` module already defines loader-independent lifecycle, input, display, and server-thread publication boundaries, but it has no concrete runtime component that owns and advances a Compukter VM program.

This design provides that missing runtime boundary. It starts from already compiled Artifact v1 bytes. Compilation, project storage, editor UI, shell semantics, and a concrete Minecraft `BlockEntity` remain separate follow-up work so that native session ownership and bounded server-tick execution can be validated independently.

## Goals

- Own exactly one native Compukter VM session for a Minecraft computer runtime.
- Advance guest execution without blocking or monopolizing the Minecraft server thread.
- Connect the v1 terminal capability to bounded, non-blocking output and line input.
- Expose stable lifecycle and failure states suitable for a future device carrier and UI.
- Keep `core` independent of K2 implementation classes and mod-loader classes.

## Non-goals

- Compile Kotlin source inside Minecraft.
- Define source-project ownership, persistence, snapshots, or deployment.
- Implement a shell, editor, screen, networking protocol, or concrete `BlockEntity`.
- Run multiple programs concurrently inside one host.
- Generalize an add-on capability API before a second capability exists.

## Public Model

`ProgramRuntimeHost` is a loader-independent, server-owned component. Its public operations are synchronous because callers invoke them on the server thread and none performs blocking I/O:

- `start(artifact: ByteArray): ProgramStartResult`
- `serverTick(): ProgramRuntimeState`
- `submitLine(line: String): Boolean`
- `drainOutput(): String`
- `shutdown()`
- `close()`

The host exposes one immutable `ProgramRuntimeState` value:

- `Idle` — no session exists.
- `Running` — a session exists and may be advanced.
- `WaitingForInput` — the session is suspended at one terminal `readln` request.
- `Halted` — the guest returned normally.
- `Failed` — admission, start, guest, VM, host, quota, or bridge execution failed with a typed `ProgramFailure`.
- `Closed` — the host permanently rejected further start, tick, and input operations.

`start` accepts canonical artifact bytes and returns either `Started` or a typed rejection. A rejected artifact never publishes `Running`. Starting while a session is active closes the old session first and starts a fresh one; output belonging to the old run is cleared. Starting a closed host is rejected.

`shutdown` closes the current session and returns to `Idle`. It is idempotent. `close` is also idempotent, closes the current session, clears pending input, and permanently enters `Closed`.

## Execution and Budgeting

`ProgramTickBudget` contains:

- `guestBudgetPerAdvance`
- `maintenanceBudgetPerAdvance`
- `maximumAdvancesPerTick`

All values are positive. A server tick calls `VmSession.advance` no more than `maximumAdvancesPerTick` times. Each call receives the two per-advance budgets. This establishes a strict upper bound even when the guest executes `while (true)` or produces a rapid sequence of host requests.

`SliceExhausted` consumes one advance and allows another advance while the tick limit remains. Immediate terminal writes are validated, buffered, and resumed within the same tick, after which execution may continue if another advance remains. A terminal read stores its request ID, publishes `WaitingForInput`, and ends the tick immediately. A terminal VM outcome ends the loop and closes the native session.

The first version deliberately does not measure wall-clock time. Native instruction and maintenance budgets are the deterministic enforcement boundary; wall-clock watchdogs can be layered above it later.

## Terminal Capability

The host handles only `compukter:terminal@1.0`:

- operation `0` (`print`) requires exactly one string argument;
- operation `1` (`println`) requires exactly one string argument and appends `\n`;
- operation `2` (`readln`) requires no arguments.

Unknown capabilities, versions, operations, or invalid argument shapes are resumed as typed host failures. The host never invokes blocking `InputStream` operations or starts a coroutine to wait for input.

Terminal output is retained as UTF-16 Kotlin text and bounded by `maximumPendingOutputCodeUnits`. `drainOutput` returns and clears all pending text. A write that would exceed the bound resumes the request with a stable output-limit host failure; the native VM then determines the terminal outcome through its normal host-failure path. Unpaired surrogate code units remain unchanged at this boundary because Artifact v1 and Kotlin string operations use UTF-16 code-unit semantics; a renderer or external byte transport owns any later replacement or encoding policy.

Only one input request may be pending because the VM is single-tasked. `submitLine` succeeds only in `WaitingForInput`, enforces `maximumInputLineCodeUnits`, resumes that exact request with the submitted Kotlin string, clears the pending request, and publishes `Running`. It does not advance the VM recursively; execution resumes on the next server tick. Rejected input leaves the pending read and state unchanged.

## Native Session Boundary

Production uses an internal `ProgramVmSession` abstraction backed by `VmSession`. The abstraction contains only `advance`, `resume`, and `close`, allowing state-machine unit tests to execute without loading JNI. Artifact bytes are defensively copied at the native session boundary as they are today.

The abstraction is not a public add-on API. Future capabilities should extend the host around explicit capability contracts rather than expose raw native handles or arbitrary compiler/runtime plugins.

## Failure Mapping

Creation failures map without string parsing:

- verifier rejection;
- admission code;
- start code;
- bridge/platform failure.

Terminal VM outcomes map to typed runtime failures:

- allocation exhaustion, including whether collection was attempted;
- quota kind, limit, and consumed count;
- guest trap;
- VM fault;
- host failure kind and code;
- invalid terminal request generated by the guest/runtime contract.

On every terminal failure or normal halt, the host closes the native session exactly once, clears a pending input request, and preserves buffered output for the caller to drain. Subsequent ticks are no-ops that return the same terminal state.

## Threading and Ownership

The future carrier owns one host and calls every public operation on its server thread. The host does not create threads, launch coroutines, or dispatch work. Consequently its mutable state requires no internal locking. Client packets and background services must first marshal calls onto the server thread through existing platform boundaries.

The compiler worker remains a separate child process managed outside this component. A later orchestration layer may compile a source snapshot asynchronously and schedule `start` with the resulting artifact back onto the server thread.

## Testing

State-machine unit tests use scripted `ProgramVmSession` instances and prove:

- invalid starts never publish `Running`;
- replacement closes the old session exactly once;
- each tick respects its maximum advance count;
- `while (true)`-style repeated `SliceExhausted` outcomes remain bounded;
- writes resume correctly and pending output is bounded and drainable;
- reads publish `WaitingForInput`, reject invalid input, and resume only on a later tick;
- halt, each failure family, shutdown, and close release the session exactly once;
- closed hosts reject later operations.

A focused real-JNI integration test reuses a committed compiler-produced terminal artifact. It advances across bounded ticks, observes the prompt and `WaitingForInput`, submits a line, then observes derived output and `Halted`.

Repository verification runs `:core:check`, `:native-runtime:check`, `verifyLocalFast`, and the relevant Rust VM tests through the existing Gradle tasks.

## Follow-ups

- A compiler orchestration service that accepts an authoritative bounded source snapshot and returns Artifact v1 without blocking the server thread.
- A concrete computer carrier that owns `ProgramRuntimeHost`, persists the appropriate state, and publishes terminal changes.
- The in-game project filesystem/editor/build/run loop tracked by #462.
