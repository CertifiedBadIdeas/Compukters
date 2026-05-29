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
local lowering. It is not yet a full caller-saved/callee-saved register
classification, and it does not define stack-passed parameters or return slots.
