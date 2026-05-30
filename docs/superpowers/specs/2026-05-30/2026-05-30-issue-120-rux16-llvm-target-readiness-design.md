# Rux16 LLVM Target Readiness Design

> Issue: [#120](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/120)

## Context

The long-term direction is to make Rux16 a real LLVM target so languages that
can emit LLVM IR can eventually produce guest programs for the Rux computer
environment.

This is not the same as implementing the LLVM backend immediately. The current
Rux16 stack is still experimental: it has a useful CPU substrate, RUXE
executables, a machine profile, storage-backed boot flow, and a growing OS
boundary, but it does not yet have the full CPU ABI, instruction coverage,
relocation model, object pipeline, or runtime surface that an LLVM backend
needs.

This spec defines the readiness target. Its purpose is to make future Rux16
VM, ABI, compiler, and tooling work converge toward a backend that is possible
to implement cleanly.

## Design Goals

- Treat Rux16 as a conventional 32-bit little-endian target.
- Keep the LLVM target scoped to CPU code generation, ABI, and object output.
- Keep Minecraft, machine profiles, MMIO devices, storage volumes, and RUXE
  executable packaging outside the LLVM backend unless LLVM must know a narrow
  boundary.
- Prefer explicit unsupported-feature errors over fallback paths.
- Make the first backend proof small enough to validate before porting libc,
  compiler-rt, Rust, or higher-level language runtimes.
- Preserve the current RUXE executable model by putting conversion/linking in
  Rux tooling instead of forcing LLVM to own the final executable container.

## Non-Goals

- No LLVM backend implementation in this slice.
- No in-tree LLVM upstreaming work.
- No immediate promise that every LLVM-producing language works.
- No libc, compiler-rt, C++, Rust, garbage collector, dynamic linker, or
  language-runtime port in this slice.
- No virtual memory, privilege rings, scheduler, process table, or full OS ABI.
- No fallback execution path for unsupported instructions or ABI features.

## Target Shape

The initial target should be modeled as:

```text
target triple:   rux16-unknown-ruxos
endianness:      little
pointer width:   32
integer width:   i1, i8, i16, i32 legal or promotable to i32
registers:       16 architectural u32 registers
stack:           guest RAM, downward-growing, 4-byte minimum slot size
code model:      static, freestanding, no dynamic linking
executable:      RUXE produced by Rux tooling after LLVM-generated objects
```

The first target does not need a hosted C environment. It should start as a
freestanding target that can compile and run leaf functions, stack-using
functions, direct calls, simple loops, and explicit memory access.

## Architecture Model

### Registers

Rux16 already defines 16 architectural `u32` registers. LLVM readiness should
split them into explicit classes:

```text
r0       return value and scratch
r1-r3    first integer/pointer arguments
r4-r11   allocatable general-purpose registers
r12      frame pointer
r13-r14  backend scratch registers
r15      stack pointer
```

This classification is an initial target model, not a final OS ABI. The
important readiness requirement is that the compiler can decide which registers
are allocatable, which are reserved, and which must survive calls.

Before backend work starts, the ABI docs must state:

- caller-saved registers;
- callee-saved registers, if any;
- reserved registers;
- how `r12` is used when frame pointers are enabled;
- whether `r13` and `r14` are backend-reserved scratch registers or normal
  allocatable registers after instruction selection.

### Calling Convention

The first LLVM-facing calling convention should support:

- `i32` and pointer arguments in `r1-r3`;
- `i32` and pointer return values in `r0`;
- stack-passed arguments after the register argument limit;
- stack locals and spills;
- direct calls and returns;
- 4-byte stack alignment at function entry.

The first slice may reject or lower later:

- `i64` arguments and returns;
- aggregate-by-value arguments;
- struct returns;
- varargs;
- exceptions;
- tail calls.

Those restrictions must be target diagnostics or documented unsupported
features, not silent behavior differences.

### Stack Frames

Rux16 already uses `r15` as `sp` and `r12` as `fp` for compiler-managed helper
frames. LLVM readiness should generalize that into a normal function frame
contract:

```text
call:
  pushes return PC through the Rux16 call instruction

callee prologue:
  optionally saves caller fp
  optionally establishes fp
  reserves stack space for locals, spills, and outgoing arguments

callee epilogue:
  restores stack space
  restores saved fp
  ret
```

The docs must make return-address ownership explicit because Rux16 `call`
currently pushes the return PC directly to guest RAM.

## Instruction Coverage

The current Rux16 instruction set is enough for the existing compiler slices,
but an LLVM backend needs a more regular integer target. The readiness target
should require these instruction families before backend implementation starts.

### Required Before Backend Work

- constants: small immediates and full 32-bit constants;
- arithmetic: `add`, `sub`;
- bitwise: `and`, `or`, `xor`, `not` or equivalent lowering;
- shifts: logical left, logical right, arithmetic right;
- comparisons: `eq`, `ne`, unsigned and signed relational comparisons;
- memory: `load8`, `load16`, `load32`, `store8`, `store16`, `store32`;
- control flow: conditional branches, unconditional jumps, direct calls,
  indirect calls if needed by the ABI, and returns;
- trap/syscall boundary: one explicit instruction or ABI mechanism for
  entering OS/runtime services;
- CSR or trap metadata only where the OS/debug ABI requires it.

### Allowed Later

- multiply and divide, if the first backend can lower them to compiler-rt
  helper calls;
- atomics;
- floating point;
- vector operations;
- dynamic linking;
- position-independent code;
- hardware privilege levels;
- virtual memory.

The backend should not start by emulating a large missing ISA through ad hoc
runtime calls. A small number of deliberate libcalls is acceptable; routine
integer operations should be native Rux16 instructions.

## Object And Executable Pipeline

LLVM should not produce RUXE directly in the first version. The preferred
pipeline is:

```text
clang / llc
  -> Rux16 relocatable object
  -> Rux linker or objcopy-style tool
  -> RUXE program/kernel/bootloader executable
  -> RuxFS or boot media installation
  -> VM loader / OS exec service
```

The relocatable object format should be chosen for LLVM implementation
practicality. ELF is the likely first choice because LLVM already has strong
ELF infrastructure. RUXE remains the guest-loadable executable container and
does not need to become a general relocatable object format.

Readiness work must define:

- section names used for code, read-only data, writable data, and zero-fill
  data;
- relocation records for calls, branches, absolute addresses, and data
  references;
- how symbols become RUXE load addresses;
- how unsupported relocation kinds fail;
- how a linked image selects RUXE ABI kind: `bootloader`, `kernel`, or
  `program`.

The first linked program can be static and single-image. Dynamic linking is out
of scope.

## Runtime Boundary

LLVM code generation does not make arbitrary languages work by itself. The
first target should separate three layers:

1. CPU target: instruction selection, register allocation, calling convention,
   object emission.
2. Freestanding runtime: compiler helper functions such as `memcpy`, `memset`,
   `memmove`, division helpers if division is not native, and startup code.
3. OS/runtime ABI: syscalls, filesystem, terminal, process startup, allocation,
   panic/exit behavior, and language-specific runtime support.

The first backend proof should only depend on layers 1 and a minimal subset of
layer 2. It should not depend on a full OS or libc.

## First Acceptance Program

The first observable backend proof should be intentionally small:

```llvm
define i32 @add(i32 %a, i32 %b) {
entry:
  %sum = add i32 %a, %b
  ret i32 %sum
}
```

The first end-to-end proof should then grow to:

```c
int main(void) {
    int x = 40;
    int y = 2;
    return x + y;
}
```

The acceptance result is not "clang supports Rux". The acceptance result is:

- LLVM IR or freestanding C lowers to Rux16 code;
- the object is linked into a RUXE `program`;
- the VM or OS exec test loads the RUXE;
- the program runs and exposes the expected `42` result through a defined
  halt, return, trap, debug, or syscall boundary.

## Follow-Up Issue Breakdown

This readiness slice produced these follow-up issues:

1. [#121](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/121):
   Complete Rux16 integer ISA for LLVM code generation.
2. [#122](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/122):
   Define Rux16 calling convention and stack ABI for LLVM.
3. [#129](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/129):
   Keep Rux16 assembler and disassembler aligned with LLVM-readiness ISA.
4. [#125](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/125):
   Define Rux16 relocatable object and relocation model.
5. [#124](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/124):
   Add Rux object-to-RUXE link step.
6. [#123](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/123):
   Add minimal freestanding Rux16 runtime helpers.
7. [#126](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/126):
   Prototype first out-of-tree LLVM backend for Rux16.
8. [#128](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/128):
   Add first clang freestanding C smoke test for Rux16.
9. [#127](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/127):
   Assess Rust `core` and `no_std` feasibility on Rux16.

The broader issue #41 remains the LLVM backend direction. This issue is the
readiness gate before backend implementation starts.

## Alternatives Considered

### A. Start The LLVM Backend Immediately

This would produce visible progress sooner, but it would force backend code to
encode unresolved VM and ABI decisions. The result would likely be brittle:
many operations would become special-case lowering, runtime calls, or temporary
assembler behavior.

### B. Make RUXE A Native LLVM Object Format

This would make the Rux toolchain look self-contained, but it would require
more custom LLVM object infrastructure before there is proof that the CPU
backend works. RUXE is a loadable executable container, not a relocatable object
format, so this is the wrong first boundary.

### C. Stabilize Rux16 As An LLVM-Friendly Target First

Recommended. Keep LLVM backend work behind a readiness gate. Stabilize the
target model, fill the minimum ISA/ABI gaps, and define the object-to-RUXE
pipeline. This keeps the backend smaller and makes each VM/ABI change useful
even before LLVM exists.

## Verification Strategy

For this design slice:

- review this spec against `docs/abi/rux16-v1.md`, `docs/abi/ruxe-v1.md`, and
  the current Rux16 VM implementation;
- ensure the roadmap issue links this spec and still names the backend work as
  future work.

For future implementation slices:

- run focused Rust tests for Rux16 decode/execute changes;
- run assembler/disassembler tests for new opcodes;
- run artifact/linker tests for relocation and RUXE conversion;
- run a VM execution test for each backend smoke program;
- run Gradle checks through `./gradlew-sandbox` only when Kotlin-facing runtime
  behavior changes.
