# Linear RAM Low-Level VM Design

## Goal

Replace the current high-level CK image execution model with a low-level, performance-oriented VM built around primitive registers, `u32` linear-memory addresses, explicit ABI calls, and compiler-managed ownership.

The VM should feel closer to a sandboxed small computer or WASM-like machine than to a JVM-like managed runtime.

## Motivation

The typed register-bank VM removed the largest scalar overhead by keeping `Int`, `Long`, and `Bool` out of `VmValue` in the hot path. The benchmark is better, but it is still far behind JVM on tight compute workloads because the runtime still carries high-level model costs:

- dynamic boundary values remain central for strings, records, and hostcalls;
- function-heavy workloads pay significant call-frame overhead;
- data structures do not have a compact memory layout;
- hostcall arguments are still marshalled as value objects;
- the language/runtime direction still assumes managed records, strings, and heap-like objects.

The desired direction is different: performance first, low-level programming allowed, no GC requirement, and a C++-like language model with RAII and move semantics.

## Non-Goals

- Do not keep old stack VM or register-bank image versions as runtime fallbacks.
- Do not design a garbage collector.
- Do not preserve high-level records/managed heap as the central storage model.
- Do not expose raw host pointers to guest programs.
- Do not support VM RAM larger than `u32::MAX` bytes.
- Do not add JIT/AOT in the first implementation slice.
- Do not preserve source compatibility with the current CKL language if it conflicts with the low-level VM direction.

## Target Architecture

The target VM has three runtime storage classes:

```text
primitive registers  -> current execution values
linear RAM           -> structs, arrays, strings, buffers, stack, globals
host resources       -> opaque handles returned by explicit hostcalls
```

There is no managed object heap in the VM execution core. If the future language wants vectors, strings, boxes, or containers, they are implemented as library/runtime data structures inside linear RAM, using explicit allocator and destructor code.

## Address Model

All guest addresses are `u32` offsets into the process linear memory:

```rust
type GuestAddr = u32;
```

This is intentional:

- the mod will not run a guest VM with more than 4 GiB of RAM;
- `u32` addresses keep instructions and structs compact;
- pointer arithmetic is cheaper and easier to inspect;
- hand-edited binaries are simpler.

Every memory access checks:

- address + size does not overflow `u32`;
- address range is inside allocated RAM;
- alignment rules, when an opcode requires alignment.

Out-of-bounds memory access is a VM error signal, not undefined behavior.

## Linear Memory

Each process owns a byte-addressable memory object:

```rust
struct LinearMemory {
    bytes: Vec<u8>,
    quota_bytes: u32,
}
```

The first implementation should use fixed-size memory per process. A later implementation can add `memory.grow` if there is a real need.

The image loader initializes memory from image sections:

```text
.text    code, not directly writable by guest code
.rodata  constants, string bytes, jump tables
.data    initialized writable globals
.bss     zero-initialized writable globals
.stack   call stack and local spill area
.heap    optional guest allocator arena
```

The first slice can keep `.text` outside RAM as decoded instructions while still loading `.rodata`, `.data`, `.bss`, stack, and heap into linear RAM.

## Register File

The low-level VM uses one unified virtual register file:

```text
r0..rN: u64
```

The register file is untyped at runtime. Type interpretation belongs to the instruction:

```text
i32.add r2, r0, r1
i64.mul r4, r5, r6
addr.add r7, r8, r9
jump_if_false r10, target
```

`i32` values and `u32` guest addresses occupy the lower 32 bits. `bool` values are represented as `0` or `1`. `i64` values use the full register. This keeps call frames, hand-edited binaries, and interpreter hot paths simple: one register index namespace, one register window per frame, and no runtime tagged values for primitive scalars.

Pointers are not VM objects. They are `u32` offsets stored in registers or memory and validated by memory instructions.

There is no `ref` register bank in the low-level VM. Strings, records, arrays, and objects are memory layouts, not VM-owned object references.

## Call Frames And Stack

The VM keeps an internal call stack for interpreter control:

```rust
struct Frame {
    function_index: usize,
    instruction_pointer: usize,
    register_base: usize,
    register_count: usize,
    return_register: Option<u16>,
    stack_base: GuestAddr,
}
```

Guest-visible stack storage lives in linear RAM. The compiler decides which locals live in registers and which spill to stack memory.

Function calls:

1. append one unified register window;
2. reserve guest stack space if the callee needs it;
3. copy or move arguments according to the function ABI;
4. jump to callee code.

Returns:

1. run explicit destructor/drop code already emitted by the compiler;
2. restore stack pointer;
3. truncate register windows;
4. write the primitive return register.

Functions do not have an implicit return instruction at the end. A function must reach an explicit `Return*` or `ReturnUnit` instruction. Falling past the last instruction is a VM error. Jump targets must point to an existing instruction; jumping to `instructions.len()` is invalid rather than a hidden `ReturnUnit`.

## Ownership, RAII, And Move Semantics

Ownership is a language/compiler property, not a VM garbage collector.

The compiler enforces:

- moved values cannot be used again;
- destructors run at scope exits;
- destructors run on early returns;
- host resource handles are closed or transferred according to their type;
- memory allocations have explicit owners.

The VM only executes explicit instructions:

```text
CallStatic drop_string
CallHost resource_close
MemFree allocator_ptr, allocation_ptr
```

There is no hidden tracing, reference counting, or finalizer pass in the VM.

## Strings And Slices

Strings are UTF-8 byte slices in linear RAM:

```text
struct StrSlice {
    ptr: u32
    len: u32
}
```

Owned strings are library-level structs:

```text
struct String {
    ptr: u32
    len: u32
    cap: u32
}
```

The VM does not know that a slice is a string unless an instruction or hostcall ABI says so. String validation is done by library code, compiler-inserted checks, or hostcall implementations where needed.

## Structs, Arrays, And Records

Records become static layouts in RAM:

```text
struct Event {
    name_ptr: u32
    name_len: u32
    id: i32
    arg_count: i32
}
```

There is no runtime `Record { type_name, fields }`.

The compiler owns layout:

- field offsets;
- alignment and padding;
- move/drop behavior;
- ABI representation.

Dynamic containers are ordinary memory-backed library types.

## Hostcall ABI

Hostcalls are import-id based and register/RAM based:

```text
CallHost import_id
```

Arguments are passed in fixed registers and/or memory according to import metadata known at compile time. Example:

```text
filesystem.readText(path_ptr: u32, path_len: u32, out_ptr: u32, out_cap: u32)
returns: status: i32, bytes_written: u32
```

The VM no longer converts hostcall arguments into `Vec<VmValue>` in the hot path.

For calls that cannot be handled fully inside Rust and must cross to the Minecraft/loader boundary, the VM can emit an explicit external syscall signal:

```text
ExternalHostCall {
    import_id,
    register_snapshot,
    memory_ranges,
}
```

This signal is not a Kotlin execution fallback. It is an explicit device/host boundary. Unsupported imports fail fast.

## Signals

The signal model remains scheduler-friendly but becomes low-level:

```text
Halt(exit_code: i32)
Pause
Yield
Sleep(ticks: i64)
WaitEvent(filter_ptr: u32, filter_len: u32)
WaitPoll(channel: i32, wake_sequence: i64)
WaitProcess(pid: i32, wake_sequence: i64)
ExternalHostCall(import_id, ABI state)
Error(message)
```

`VmValue` should no longer be the standard signal payload for program values. Debug tooling may still decode values from memory using type metadata.

## Instruction Set Direction

The low-level VM instruction set should be compact and memory-first:

```text
I32Const dst, imm32
I64Const dst, imm64
AddrConst dst, imm32

I32Add dst, lhs, rhs
I32Sub dst, lhs, rhs
I32Mul dst, lhs, rhs
I32Div dst, lhs, rhs
I32And dst, lhs, rhs
I32Or  dst, lhs, rhs
I32Xor dst, lhs, rhs
I32Shl dst, lhs, rhs
I32Shr dst, lhs, rhs

Load8  dst_i32, addr
Load16 dst_i32, addr
Load32 dst_i32, addr
Load64 dst_i64, addr
Store8  addr, src_i32
Store16 addr, src_i32
Store32 addr, src_i32
Store64 addr, src_i64

AddrAdd dst_addr, base_addr, offset_i32
MemCopy dst_addr, src_addr, len_i32
MemFill dst_addr, byte_i32, len_i32

Jump target
JumpIfZero i32_cond, target
JumpIfNonZero i32_cond, target

CallStatic function_index
Return
CallHost import_id
```

The initial implementation can keep a typed decoded enum. A later optimization pass should pack common opcodes into cache-friendly fixed-width instructions.

## Image ABI

Introduce a new active image ABI:

```text
CKIM version 5: linear-RAM low-level VM image with unified `u64` registers
```

The Rust decoder rejects previous versions. There is no runtime dispatch to `CKIM v1`, `v2`, or `v3`.

The image contains:

- language/ABI version;
- memory size/quota request;
- rodata/data/bss layout;
- import table keyed by import id;
- function table;
- register counts per function;
- stack requirements per function;
- decoded or encoded instruction stream;
- optional debug/type metadata.

Debug/type metadata is optional and must not be required for execution.

## Image Validation

The Rust loader validates low-level images before constructing mutable VM state. This keeps malformed binaries fail-fast and lets the interpreter hot path rely on structural invariants.

The validator checks:

- entry function index exists;
- memory sections fit inside requested linear RAM;
- every function has at least one instruction;
- every function ends with `Jump` or explicit `Return*`/`ReturnUnit`;
- parameter, operand, argument, and return register indices are inside the function register window;
- jump targets point to existing instructions;
- static call targets point to existing functions;
- static call argument count matches the callee parameter count.

Runtime checks still remain for data-dependent behavior such as division by zero and linear-memory bounds. The validator is not a Kotlin fallback or compatibility layer; invalid images are rejected before execution.

## Compiler Direction

The source language may become C++-like:

- value types and explicit layouts;
- references/slices as pointer+length pairs;
- RAII destructors;
- move semantics;
- no implicit GC;
- explicit allocation APIs;
- explicit host resource handles.

The compiler lowers high-level ownership rules into explicit low-level operations. Runtime safety comes from:

- compile-time ownership checks;
- VM memory bounds checks;
- hostcall ABI validation;
- fail-fast process errors.

## Performance Strategy

The low-level VM targets performance through:

- no dynamic value objects in execution;
- compact memory layouts;
- no managed heap dispatch;
- hostcalls without `VmValue` marshalling;
- one unified `u64` register file and predictable memory instructions;
- future packed instruction representation;
- future superinstructions;
- future AOT/JIT if needed.

Linear RAM alone does not remove interpreter dispatch overhead, but it removes the high-level object model cost and makes later packed IR, superinstructions, and AOT much easier.

## Execution Slicing

The low-level VM uses a hybrid time-slice model:

- the public slice budget is wall-clock time, expressed in nanoseconds;
- the interpreter checks elapsed time only every fixed instruction interval;
- the fixed interval is timer amortization, not an instruction quota fallback;
- `Pause` means the current wall-clock slice is exhausted and the scheduler should resume the same VM later.

The device daemon uses the same wall-time model. Kotlin refills an execution window with `wallTimeGuardNanosPerSlice`; Rust keeps server tick state, wakes sleepers, and runs runnable processes until the wall-time window is exhausted, no runnable work remains, or a fixed scheduler-turn safety cap is reached. The turn cap is not a speed/quota knob and is not profile-configurable. It only prevents pathological yield loops from monopolizing the daemon executor.

This keeps server-time control closer to real CPU cost than a raw instruction counter while avoiding `Instant::now()` on every instruction. Expensive instructions, memory operations, and future hostcall boundaries are naturally charged by elapsed time instead of by a synthetic opcode count.

Runtime benchmark metrics stay time-based:

- VM elapsed nanoseconds;
- run invocations;
- pause signals.

Detailed opcode counters and instructions-per-iteration metrics are intentionally not part of the low-level VM hot path. If we need deep profiling later, it should be a separate profiling build or sampling tool, not production interpreter state.

## Migration Strategy

This is a replacement architecture, not a compatibility layer.

1. Keep current register-bank VM only long enough to preserve benchmark and daemon behavior while building v5.
2. Add `CKIM v5` model and decoder tests.
3. Add a tiny v5 compiler backend for compute-only programs.
4. Add a v5 Rust runner with unified `u64` registers and fixed linear RAM.
5. Port benchmark workloads to v5 and compare against current numbers.
6. Add hostcall ABI for system, process, filesystem, display, strings, events, and IPC.
7. Replace bundled ROM programs with v5 images.
8. Delete v3 runtime paths after parity.

## Success Criteria

- The VM executes compute benchmarks without `VmValue` in the hot path.
- `u32` linear RAM is the only guest data storage model.
- Strings and records are represented as RAM layouts, not managed VM objects.
- Hostcalls use import ids plus register/RAM ABI state.
- Unsupported image versions and unsupported imports fail fast.
- Benchmark reports show elapsed VM time, run invocations, and pause counts without runtime opcode counters.
- The VM remains safe under malicious guest code through bounds checks and fail-fast errors.
