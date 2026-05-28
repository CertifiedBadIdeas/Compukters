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

`r0` is a normal writable register. There is no zero register in v1.

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

## Target Stack Ownership

All Rux16 targets use the same stack convention, but the stack top is provided
by different layers:

```text
bios        firmware-owned temporary stack if needed
boot        BIOS or boot entry contract initializes boot stack top
kernel      bootloader passes or installs kernel stack top before entry
program     kernel allocates and initializes user stack before entry
```

The current implementation only reserves the convention and proves the memory
behavior. Full call/return lowering, kernel/user privilege separation, and
process stack allocation are later ABI slices.

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

The return address is the next instruction after `call`. Stack load/store
faults are normal Rux16 CPU faults. The first ABI slice does not add stack
bounds metadata or a fallback return path.

## Compiler-Generated Register Saves

When the Rux16 compiler emits a real helper call and the caller has live local
registers, it saves those local registers to the `r15` stack before `call` and
restores them after `ret`.

This is a compiler-owned preservation rule for the current register-backed
local lowering. It is not yet a full caller-saved/callee-saved register
classification, and it does not define stack-passed parameters or return slots.
