# Guest Suspend Call Execution

> Issue: [#525](https://github.com/CertifiedBadIdeas/Compukters/issues/525)

## Context

The Kotlin lowering pipeline emits `CallSuspend` when one guest `suspend` function invokes another. The artifact model, encoder, decoder, Kotlin validator, and Rust verifier already understand that instruction and its explicit `resumeBlock`. Rust execution admission stops at `ExecutionImage`: it does not resolve `CallSuspend`, and `Machine` therefore cannot execute it.

Direct calls already use a bounded, preallocated frame stack. Asynchronous capability calls already suspend the active frame and resume it at an explicit block. `CallSuspend` must compose those two mechanisms without introducing a second coroutine runtime or allocating host objects per call.

## Goal

Execute verified guest-to-guest suspend calls with deterministic, bounded frame accounting. A callee may wait on asynchronous host capabilities, return `Unit` or a value, and resume its caller exactly once at the encoded continuation block.

## Accepted Architecture

### Explicit return continuation

Each non-entry runtime frame records an explicit caller continuation composed of a block and instruction index, in addition to its result destination.

- `CallDirect` records the caller's current block and the next instruction.
- `CallSuspend` records the instruction-zero position of its encoded `resumeBlock`.
- Returning from either call pops and clears the callee frame, restores the recorded caller position, and writes the returned value to the caller destination when one exists.

The continuation is explicit rather than represented by mutating the suspended caller before entering the callee. This keeps frame state self-describing for diagnostics, future snapshots, and corruption checks.

### Reuse the bounded frame stack

`CallSuspend` enters the target exactly like a direct call:

1. Resolve and validate the target and continuation during execution-image admission.
2. Validate source registers and the target parameter count.
3. Reject entry when the manifest call-depth limit is exhausted.
4. Clear the preallocated callee register window and copy arguments into parameters.
5. Push a frame containing the target entry block, destination, and explicit return continuation.

No host heap allocation, coroutine object, scheduler task, or second stack is introduced. The active top frame remains the only runnable guest context.

### Capability suspension inside a suspend callee

If the callee reaches `CapabilityCallAsync`, the existing host-request path suspends that callee frame. Completing the capability writes its result and moves the callee to its capability resume block. A later guest `Return` pops the callee and applies the caller continuation. Nested suspend calls work by repeating the same frame rule.

## Admission and Type Safety

`ExecutionImage` gains a resolved suspend-call form containing:

- destination register or Unit sentinel;
- resolved function index;
- resolved argument registers;
- resolved resume block index.

Admission must fail deterministically when the function or resume block cannot be resolved. Existing artifact verification remains authoritative for suspending target flags, argument types, destination/result agreement, data-flow initialization, and continuation ownership. Runtime checks remain defensive against corrupted internal state.

## Failure and Accounting Semantics

- Stack-depth exhaustion produces the existing `StackOverflow` guest trap before a new frame becomes visible.
- Invalid resolved IDs, storage layouts, values, or continuation state produce the existing bounded VM faults.
- A callee trap or VM fault terminates the single task; the caller is not resumed.
- Host failure during a capability wait propagates through the existing terminal outcome path.
- Fixed instruction cost and maximum observed frame depth are accounted exactly as for direct calls; waiting itself consumes no guest budget.
- Session shutdown or cancellation drops the complete bounded machine state. No separate suspend-call resource requires cleanup.

## Compatibility

The artifact wire format does not change. Existing direct calls and asynchronous capability calls retain their observable behavior. The change only admits and executes an instruction already present in the format and verifier.

## Verification

Tests are compiler-first where possible and fixture-based where invalid states cannot be emitted by K2:

- a compiler-produced `Unit` suspend caller/callee artifact executes and resumes;
- a value-returning suspend call writes the selected result before continuing;
- a suspend callee performs an asynchronous terminal capability call and resumes through both continuations;
- nested suspend calls preserve return order;
- exact-depth execution succeeds and one additional call traps with `StackOverflow`;
- malformed target, destination, argument, or resume block artifacts are rejected deterministically;
- direct-call and capability-call regression suites remain unchanged;
- Rust unit tests, compiler-to-VM conformance, FFM integration, and local fast verification pass.

## Out of Scope

- Multiple concurrently runnable coroutines or guest processes.
- `CoroutineSpawn`, `CoroutineJoin`, sleep, cancellation handlers, or structured concurrency.
- A new artifact version or bytecode instruction.
- General VM snapshot implementation; the frame representation merely remains compatible with that future direction.

