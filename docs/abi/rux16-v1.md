# Rux16 v1 CPU ABI

## Status

Status: experimental.

Rux16 is the active guest instruction-memory execution substrate for BIOS
flash, bootloader, and kernel artifacts. Instruction bytes live in guest-visible
memory, and the CPU fetches, decodes, and executes them from `pc`.

## Register File

Rux16 has 16 architectural registers:

```text
r0..r15  u32 registers
```

The compiler-owned helper call ABI currently assigns:

```text
r0      return value
r1..r3  first three helper arguments
r12     helper frame pointer
```

At helper entry, the compiler copies incoming `r1..r3` argument values into
stable helper-local storage before lowering the helper body. Helper body code
must not rely on parameters remaining in their incoming call ABI registers after
the prologue.

There is no zero register in v1.

## Initial LLVM-Facing Target Model

The first LLVM-facing target is expected to model Rux16 as:

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

This is a readiness contract, not a statement that the LLVM backend exists.
Until the target backend and object pipeline exist, tooling must reject
LLVM-facing requests explicitly instead of falling back to another execution
path.

The initial LLVM register classification is:

```text
r0       return value and scratch
r1-r3    first integer or pointer arguments
r4-r11   allocatable general-purpose registers
r12      frame pointer when frame pointers are enabled
r13-r14  backend scratch registers for instruction selection and lowering
r15      stack pointer
```

`r12`, `r13`, `r14`, and `r15` are reserved for backend or frame management in
the initial target model. General-purpose allocation must not use those
registers until a later ABI revision deliberately changes the contract.

The first LLVM-facing calling convention is intentionally narrow:

- `i32` and pointer arguments enter in `r1`, `r2`, and `r3`;
- `i32` and pointer return values leave in `r0`;
- additional arguments are passed on the `r15` stack in 4-byte slots;
- stack locals and spills use normal guest RAM;
- direct calls use `call rN`, and returns use `ret`;
- the stack is 4-byte aligned at function entry.

The initial call-preservation model is:

```text
caller-saved:  r0-r11
reserved:      r12-r15
callee-saved:  none in the first LLVM-facing ABI slice
```

This model keeps the first backend simple and makes all preservation explicit
in generated caller code. A later ABI may add callee-saved registers, but code
that depends on callee preservation before that revision is invalid.

The initial target must reject or lower through deliberate helper/runtime
symbols, not silent fallback behavior:

- `i64` arguments and returns;
- aggregate-by-value arguments;
- struct returns;
- varargs;
- exceptions;
- tail calls;
- dynamic linking;
- position-independent code.

The first backend proof only needs freestanding static code that can run leaf
functions, stack-using functions, direct calls, simple loops, explicit loads
and stores, and a defined halt, return, trap, debug, or syscall result path.

## LLVM Readiness Instruction Set

The current Rux16 implementation does not yet provide the whole integer
instruction surface expected by the initial LLVM target. Before backend work
starts, the active CPU, assembler, disassembler, and compiler tooling should
cover these instruction families:

- constants: small immediates and full 32-bit constants;
- arithmetic: `add` and `sub`;
- bitwise operations: `and`, `or`, `xor`, and `not` or an equivalent lowering;
- shifts: logical left, logical right, and arithmetic right;
- comparisons: `eq`, `ne`, unsigned relational comparisons, and signed
  relational comparisons;
- memory: `load8`, `load16`, `load32`, `store8`, `store16`, and `store32`;
- control flow: conditional branches, unconditional jumps, direct calls, and
  returns;
- trap/syscall boundary: an explicit trap mechanism or ABI path for entering
  OS/runtime services;
- CSR access only where the OS/debug ABI requires it.

Multiplication, division, atomics, floating point, vector operations, hardware
privilege levels, and virtual memory are allowed to land after the first LLVM
proof. If a missing operation is routed through a helper call, that helper must
be named, linked, and tested as part of the freestanding runtime boundary.

## Stack Pointer

`r15` is reserved as the stack pointer:

```text
r15  sp
```

The stack:

- lives in normal guest RAM;
- grows toward lower addresses;
- uses 4-byte slots in v1;
- is read and written with ordinary Rux16 load/store instructions.

There are no dedicated `push` or `pop` opcodes in the first stack ABI slice.
Code generation must lower stack operations explicitly:

```text
push u32:
  sp = sp - 4
  store32 [sp], value

pop u32:
  value = load32 [sp]
  sp = sp + 4
```

Compiler backends must not use `r15` as a scratch register. If stack setup has
not happened for a target, code that needs stack storage must fail explicitly
instead of silently using a different register or fallback execution path.

## Helper Frame Pointer

`r12` is reserved as the compiler-owned helper frame pointer:

```text
r12  fp
```

Real helper bodies save the caller frame pointer, then set `fp` to the current
stack pointer:

```text
push fp
fp = sp
```

Before returning, helpers restore the stack to the frame base, pop the caller
frame pointer, and then execute `ret`:

```text
sp = fp
pop fp
ret
```

Compiler-managed helper locals may live in 4-byte stack slots below `fp`:

```text
[fp + 0]   saved caller fp
[fp - 4]   local slot 0
[fp - 8]   local slot 1
...
```

Stack-backed helper locals are addressed by adding a negative 32-bit offset to
`fp`, then using ordinary Rux16 load/store instructions. The helper epilogue
sets `sp = fp`, so all local slots are discarded before the saved caller frame
pointer is popped.

Helper-local byte arrays are also frame-backed. `[u8; N]` arrays reserve `N`
bytes rounded up to the next 4-byte stack slot boundary, and elements are
addressed as:

```text
array_base = fp - array_offset
element    = array_base + index
```

The current compiler slice supports direct byte load/store for local
`[u8; N]` arrays inside helper bodies. Arrays are not passed as helper
arguments, returned from helpers, heap allocated, or exposed through the
user-space ABI yet.

Helpers may also compute a guest address for a local byte-array element:

```text
&mut array[index] => array_base + index
```

That address is a normal `u32` guest RAM address and can be consumed by
`ptr<u8>(addr)` operations. This is intended for device-facing buffers, such as
passing a helper-owned block buffer to storage MMIO code. The current slice does
not add general pointer arithmetic or pointer-passed helper parameters.

The current frame slice does not add stack-passed arguments, recursion, or
return slots. User-space programs use the same compiler-owned helper stack
convention as other Rux16 targets, with process ownership defined by the OS exec
service rather than by helper call lowering.

## Target Stack Ownership

All Rux16 targets use the same stack convention, but the stack top is provided
by different layers:

```text
bios        firmware-owned temporary stack if needed
boot        BIOS or boot entry contract initializes boot stack top
kernel      bootloader passes or installs kernel stack top before entry
program     compiler profile initializes the first user stack top at 0x00010000
```

The current implementation only reserves the convention and proves the memory
behavior. Kernel/user privilege separation and dynamic per-process stack
allocation are later ABI slices.

## Call And Return

`call rN` transfers control to the address in `rN` and pushes the return PC to
the stack:

```text
sp = sp - 4
store32 [sp], return_pc
pc = rN
```

`ret` pops the return PC from the stack and transfers control back:

```text
return_pc = load32 [sp]
sp = sp + 4
pc = return_pc
```

The return address is the next instruction after `call`. Helper return values
are passed in `r0`; the first three helper arguments enter the callee in `r1`,
`r2`, and `r3`, then the compiler-owned callee prologue copies them into stable
local storage. Stack load/store faults are normal Rux16 CPU faults. The first
ABI slice does not add stack bounds metadata, stack-passed arguments, or a
fallback return path.

## Compiler-Generated Register Saves

When the Rux16 compiler emits a real helper call and the caller has live local
registers, it saves those local registers to the `r15` stack before `call` and
restores them after `ret`.

Compiler backends must not use `r12` or `r15` as scratch or local registers.
This is a compiler-owned preservation rule for the current register-backed
local lowering. It is narrower than the initial LLVM-facing target model above:
the current Rux compiler save path does not yet implement stack-passed
parameters, return slots, or a general-purpose external calling convention.
