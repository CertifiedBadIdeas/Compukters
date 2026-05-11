# Typed Register VM Rewrite Design

## Goal

Replace the current CKL stack image VM with a typed, predecoded, register-style Rust VM while preserving the existing language, hostcall/signal boundary, daemon scheduler, filesystem/display model, and benchmark infrastructure.

## Motivation

The current Rust image runner is a stack VM with dynamic `VmValue` traffic in the hot path:

- bytecode is decoded during execution;
- integer operations go through generic value dispatch;
- locals and stack values are represented as `VmValue`;
- function calls allocate/copy local vectors through call frames.

This is simple and flexible, but the compute benchmark shows the VM spends most of its time in interpreter/value/call machinery rather than CPU arithmetic. Because the mod is still in deep alpha, it is worth changing the execution architecture instead of only polishing the current stack VM.

## Non-Goals

- Do not add a JIT in this phase.
- Do not replace the Minecraft-facing daemon scheduler in the first slice.
- Do not expose raw pointers or unsafe host memory to CKL programs.
- Do not make all CKL values live in linear RAM.
- Do not keep the old stack VM as a runtime fallback.
- Do not keep Kotlin execution fallbacks.

## Architecture

The replacement pipeline is:

```text
CKL frontend
  -> existing typed bytecode module
  -> typed register CK image encoder
  -> Rust typed register image decoder
  -> predecoded functions
  -> typed register interpreter
  -> existing VM signals: Halt, Yield, Sleep, Wait*, HostCall, Error
```

This is a rewrite, not a fallback architecture. The old stack VM may be referenced by temporary parity tests during development, but production/runtime entry points should move to the register VM and fail fast if the register VM cannot execute an image.

## Register Frames

Each function has a fixed register count. Arguments and locals are assigned to registers by the compiler/lowerer. A call frame stores:

```rust
struct RegisterFrame {
    function_index: usize,
    instruction_pointer: usize,
    base_register: usize,
    return_register: Option<u16>,
}
```

The VM stores all active frame registers in one contiguous `Vec<ValueSlot>`. Function calls append a register window; returns truncate it back to the caller frame and write the return value into `return_register`.

This avoids per-call `Vec<VmValue>` locals and avoids stack push/pop traffic for most expressions.

## Value Representation

The interpreter uses a compact slot type:

```rust
enum ValueSlot {
    Unit,
    Null,
    Bool(bool),
    I32(i32),
    I64(i64),
    String(HeapId),
    Object(HeapId),
}
```

Typed opcodes such as `I32Add` read and write `I32` slots directly. If a slot has the wrong runtime type, the VM returns an `Error` signal. This preserves safety while making the hot path cheap for integers and booleans.

## Heap And Linear RAM

VM internals use a managed heap for strings, arrays, records, lists, maps, and future objects:

```rust
enum HeapObject {
    String(String),
    Array(Vec<ValueSlot>),
    Record { type_name: String, fields: Vec<ValueSlot> },
    List(Vec<ValueSlot>),
    Map(Vec<(ValueSlot, ValueSlot)>),
}
```

Linear RAM is a separate subsystem, not the storage model for all values:

```rust
struct LinearMemory {
    bytes: Vec<u8>,
    quota_bytes: usize,
}
```

CKL can later expose buffers through safe APIs or hostcalls using handles, offsets, and lengths. VM execution remains typed and register-based.

## Instruction Set

The register VM starts with typed instructions that cover the current compute benchmark and basic language constructs:

```text
LoadConst dst, const_id
LoadUnit dst
LoadNull dst
LoadBool dst, value
Move dst, src

I32Add dst, lhs, rhs
I32Sub dst, lhs, rhs
I32Mul dst, lhs, rhs
I32Div dst, lhs, rhs
I32BitAnd dst, lhs, rhs
I32BitOr dst, lhs, rhs
I32BitXor dst, lhs, rhs
I32Shl dst, lhs, rhs
I32Shr dst, lhs, rhs
I32Eq dst, lhs, rhs
I32Lt dst, lhs, rhs
I32Le dst, lhs, rhs
I32Gt dst, lhs, rhs
I32Ge dst, lhs, rhs

BoolNot dst, src
BoolAnd dst, lhs, rhs
BoolOr dst, lhs, rhs

Jump target
JumpIfFalse cond, target
JumpIfTrue cond, target

CallStatic return_dst, function_index, arg_registers
Return src
ReturnUnit

CallHost return_dst, import_id, arg_registers
Yield
Sleep ticks_reg
```

Later stages add string/record/list/map/array instructions and collection methods.

## Image ABI Strategy

Use one active image ABI after the rewrite:

- `CKIM` version `2`: typed register image format.

The compiler should emit the typed register format by default once the first full slice lands. The Rust decoder should reject unsupported or legacy image versions with a clear error instead of dispatching to the old stack runner.

## Hostcalls And Signals

The register VM keeps the same external signal protocol:

- `Halt(value)`
- `Pause`
- `Yield`
- `Sleep(ticks)`
- `WaitEvent(filter)`
- `WaitPoll(channel, wakeSequence)`
- `WaitProcess(pid, wakeSequence)`
- `HostCall(module, function, args)`
- `Error(message)`

This keeps the daemon scheduler, process table, display pump, filesystem hostcalls, and Kotlin/JNI bridge stable while the execution core is replaced.

## Rollout

1. Add typed register image data model and ABI tests.
2. Replace the Kotlin image compiler output with typed register images for a compute-only subset.
3. Replace the Rust image runner internals with a typed register decoder and image-only runner.
4. Run compute benchmark parity against Kotlin/Python/Rust baselines.
5. Add control flow and function calls.
6. Add hostcall/scheduler signal support.
7. Add heap-backed strings and collections.
8. Move ROM tests to the register VM.
9. Delete old stack opcodes, stack image ABI support, and stack runner data structures.

## Success Criteria

- Compute benchmark runs on the register VM and matches Kotlin/JVM, Python, and Rust baselines.
- The register VM supports `yield`, `sleep`, and hostcall signals through the same JNI protocol.
- Bundled ROM image compiles to the register image format.
- NeoForge tests pass with only the register VM enabled.
- Old stack VM code, old stack opcodes, and old image execution paths are removed.
- Unsupported legacy images fail fast with a clear error.
