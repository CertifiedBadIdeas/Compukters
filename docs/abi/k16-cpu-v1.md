# K16 v1 CPU ABI

## Status

Status: experimental.

K16 is the active guest instruction-memory execution substrate for BIOS
flash, bootloader, and kernel artifacts. Instruction bytes live in guest-visible
memory, and the CPU fetches, decodes, and executes them from `pc`.

## Register File

K16 has 16 architectural registers:

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

The first LLVM-facing target is expected to model K16 as:

```text
target triple:   k16-unknown-kraftos
endianness:      little
pointer width:   32
integer width:   i1, i8, i16, i32 legal or promotable to i32
registers:       16 architectural u32 registers
stack:           guest RAM, downward-growing, 4-byte minimum slot size
code model:      static, freestanding, no dynamic linking
executable:      K16E produced by K16 tooling after LLVM-generated objects
```

This is a readiness contract, not a statement that the LLVM backend exists.
Until the target backend and object pipeline exist, tooling must reject
LLVM-facing requests explicitly instead of falling back to another execution
path.

K16 VM ownership remains independent from LLVM. The VM defines and executes
the instruction set, CPU state, traps, memory accesses, and device-visible ABI.
LLVM-facing code must live in compiler/toolchain layers that produce K16
instructions and relocatable objects from outside the VM. The VM implementation
must not depend on LLVM libraries, LLVM IR, LLVM object internals, target
backend data structures, or special execution hooks for LLVM-generated code.
If LLVM output cannot be represented as normal K16 code and ABI data, the
toolchain must fail before the VM run boundary.

The initial LLVM register classification is:

```text
r0       first return value and scratch
r1-r3    first integer or pointer arguments, or additional return values
r4-r11   allocatable general-purpose registers
r12      frame pointer when frame pointers are enabled
r13-r14  backend scratch registers for instruction selection and lowering
r15      stack pointer
```

`r12`, `r13`, `r14`, and `r15` are reserved for backend or frame management in
the initial target model. General-purpose allocation must not use those
registers until a later ABI revision deliberately changes the contract.

### LLVM-Facing Function Value Model

The LLVM-facing ABI uses one 32-bit ABI slot for `i1`, `i8`, `i16`, `i32`, and
pointer values. Narrow integer arguments and return values are zero-extended to
the low bits of their 32-bit ABI slot. Signed operations on narrow source
values must explicitly perform signed interpretation during lowering; the ABI
slot itself does not carry signedness.

The first value model supports up to four scalar `i32` return slots in
`r0..r3`. This covers `i64` returns and LLVM/Rust scalar pair-style returns
such as small result/status aggregates without adding a hidden return pointer.
Aggregate-by-value arguments, memory-returned structs, varargs, and implicit
return slots are unsupported in v1.

### LLVM-Facing Register Calling Convention

The register call ABI is:

- `r0` receives return value 0 and is also a scratch register;
- `r1`, `r2`, and `r3` receive logical arguments 0, 1, and 2 on function
  entry;
- `r1`, `r2`, and `r3` receive return values 1, 2, and 3 on function return;
- `r4-r11` are allocatable general-purpose registers;
- `r12` is the frame pointer when frame pointers are enabled;
- `r13-r14` are backend scratch registers for instruction selection and
  lowering;
- `r15` is the stack pointer.

`r0-r11 are caller-saved`. `r12-r15 are reserved`. There are no callee-owned
preservation registers in v1: `no registers are callee-saved`.

The initial call-preservation table is:

```text
caller-saved:  r0-r11
reserved:      r12-r15
callee-saved:  none in the first LLVM-facing ABI slice
```

This model keeps the first backend simple and makes all preservation explicit
in generated caller code. A later ABI may add callee-saved registers, but code
that depends on callee preservation before that revision is invalid.

### LLVM-Facing Stack Arguments

Arguments are numbered in source order. Logical arguments 0, 1, and 2 use
`r1`, `r2`, and `r3`. Logical argument 3 is stack argument 0, logical argument
4 is stack argument 1, and so on.

The caller reserves the outgoing stack-argument area in its call frame and
stores stack-passed arguments before `call`:

```text
store32 [sp + 0], stack_arg0
store32 [sp + 4], stack_arg1
...
call target
```

`call` pushes the return PC after the outgoing stack-argument area has been
reserved. On callee entry:

```text
[sp + 0]  return_pc
[sp + 4]  stack argument 0
[sp + 8]  stack argument 1
...
```

Therefore stack argument 0 is at `[sp + 4]` for a callee that addresses
arguments relative to `sp` before installing a frame pointer. The `ret`
instruction pops only the return PC; the caller removes the outgoing
stack-argument area when its call frame is released.

The LLVM-facing ABI requires `sp` to be 8-byte aligned on function entry.
Because K16 `call` pushes a 4-byte return PC, code that calls another function
must present `sp % 8 == 4` immediately before `call`. The LLVM backend keeps
this true with a fixed 4-byte body pad in non-leaf functions, leaving local
frame indexes stable. Hand-written entry stubs, including `k16-startup`, must
reserve the same 4-byte pad around their direct `call main`.

### LLVM-Facing Frame Layout

When a callee uses `r12` as a frame pointer, it saves the caller frame pointer
below the return PC and sets `fp` to that saved slot:

```text
sp = sp - 4
store32 [sp], old_fp
fp = sp
```

The resulting frame layout is:

```text
[fp + 0]   saved caller fp
[fp + 4]   return_pc
[fp + 8]   stack argument 0
[fp + 12]  stack argument 1
[fp - 4]   local/spill slot 0
[fp - 8]   local/spill slot 1
...
```

Therefore stack argument 0 is at `[fp + 8]` after the frame pointer prologue.
Before returning, the callee restores `sp` to the return address slot and lets
`ret` pop the return PC:

```text
sp = fp
old_fp = load32 [sp]
sp = sp + 4
ret
```

### Current Rux Compiler Boundary

The current Rux source compiler helper-call lowering supports only `r1`-`r3`
arguments. It must reject helper calls that need stack-passed arguments until
stack-argument lowering is deliberately implemented. It still supports
stack-backed helper locals and local byte arrays inside helper frames.

The initial target must reject or lower through deliberate helper/runtime
symbols, not silent fallback behavior:

- aggregate-by-value arguments;
- struct returns;
- varargs;
- exceptions;
- stack bounds metadata;
- callee cleanup;
- tail calls;
- dynamic linking;
- position-independent code.

The first backend proof only needs freestanding static code that can run leaf
functions, stack-using functions, direct calls, simple loops, explicit loads
and stores, and a defined halt, return, trap, debug, or syscall result path.

## LLVM Readiness Instruction Set

The K16 integer instruction surface is intentionally regular so an external
LLVM backend can lower ordinary integer machine operations without introducing
LLVM-specific behavior inside the VM. The active CPU, assembler, disassembler,
and compiler tooling cover these instruction families:

- constants: small immediates, full 32-bit constants, and PC-relative 32-bit
  address materialization;
- arithmetic: `add`, `sub`, `mul`, `mulh_u`, and `mulh_s`;
- bitwise operations: `and`, `or`, `xor`, and `not` or an equivalent lowering;
- shifts: logical left, logical right, and arithmetic right;
- comparisons: `eq`, `ne`, unsigned relational comparisons, and signed
  relational comparisons;
- memory: `load8`, `load16`, `load32`, `store8`, `store16`, and `store32`;
- signed 16-bit immediates for compact stack/frame code: `addi` and
  base-plus-offset load/store forms;
- control flow: conditional branches, unconditional jumps, direct calls, and
  returns;
- trap/syscall boundary: an explicit trap mechanism or ABI path for entering
  OS/runtime services;
- CSR access only where the OS/debug ABI requires it.

Multiplication, division, atomics, floating point, vector operations, hardware
privilege levels, and virtual memory are allowed to land after the first LLVM
proof. If a missing operation is routed through a helper call, that helper must
be named, linked, and tested as part of the freestanding runtime boundary.

### Wide Immediate Encoding

Wide immediate instructions use a three-word encoding. The first word selects
the destination register and subopcode; the following two words carry a
little-endian 32-bit immediate:

```text
word 0: 0xea0s
word 1: imm32[15:0]
word 2: imm32[31:16]

a  destination register
s  wide-immediate subopcode
```

```text
s    mnemonic   semantics
0x1  const32    rA = imm32
0x2  pcadd32    rA = current_instruction_pc + imm32, wrapping u32
```

`pcadd32` exists so shared-library code can materialize internal addresses
relative to its mapped PC instead of embedding an absolute virtual address in
read-only text. Linkers may rewrite internal `const32 symbol` materialization
sites to `pcadd32` when emitting shareable code. Subopcodes outside this table
are reserved and must decode as illegal instructions.

### Integer ALU Encoding

Register-register integer ALU instructions use a canonical two-word encoding:

```text
word 0: 0x2a0s
word 1: 0x00bc

a  destination register
s  ALU subopcode
b  left operand register
c  right operand register
```

The high byte of word 1 is reserved and must be zero. Encoders must not emit
non-zero reserved bits, and decoders must reject them as illegal instructions.

```text
s    mnemonic    semantics
0x0  add         dst = lhs + rhs, wrapping u32
0x1  sub         dst = lhs - rhs, wrapping u32
0x2  and         dst = lhs & rhs
0x3  or          dst = lhs | rhs
0x4  xor         dst = lhs ^ rhs
0x5  shl         dst = lhs << (rhs & 31)
0x6  shr         dst = lhs >> (rhs & 31), logical
0x7  sar         dst = lhs >> (rhs & 31), arithmetic i32
0x8  eq          dst = lhs == rhs ? 1 : 0
0x9  ne          dst = lhs != rhs ? 1 : 0
0xa  ltu         dst = unsigned(lhs) < unsigned(rhs) ? 1 : 0
0xb  lt_s        dst = signed(lhs) < signed(rhs) ? 1 : 0
0xc  mul         dst = lhs * rhs, wrapping u32
0xd  mulh_u      dst = high 32 bits of unsigned(lhs) * unsigned(rhs)
0xe  mulh_s      dst = high 32 bits of signed(lhs) * signed(rhs)
```

There are no compatibility aliases for older experimental encodings. The VM
recognizes the documented encoding only.

### Extended Immediate Encoding

Extended immediate instructions use a two-word encoding. The low nibble of
word 0 selects the subopcode, and word 1 carries a signed 16-bit immediate:

```text
word 0: 0x3abs
word 1: signed imm16

a  destination register for addi/load, base register for store
b  source/base register for addi/load, source register for store
s  extended immediate subopcode
```

The signed immediate is sign-extended to `i32` and added with wrapping `u32`
semantics where it participates in address or arithmetic calculation.

```text
s    mnemonic                 semantics
0x1  test_bits rA, rB, imm16  rA = (rB & imm16) != 0 ? 1 : 0
0x2  addi rA, rB, imm16       rA = rB + sign_extend(imm16)
0x3  load8 rA, [rB + imm16]   rA = zero_extend_u8([rB + sign_extend(imm16)])
0x4  load16 rA, [rB + imm16]  rA = zero_extend_le_u16([rB + sign_extend(imm16)])
0x5  load32 rA, [rB + imm16]  rA = le_u32([rB + sign_extend(imm16)])
0x6  store8 [rA + imm16], rB  [rA + sign_extend(imm16)] = low_u8(rB)
0x7  store16 [rA + imm16], rB [rA + sign_extend(imm16)] = low_le_u16(rB)
0x8  store32 [rA + imm16], rB [rA + sign_extend(imm16)] = le_u32(rB)
```

Subopcodes outside this table are reserved and must decode as illegal
instructions until an ABI revision assigns them.

### Integer Memory Width Encoding

K16 load/store width is encoded in the low nibble of the memory instruction:

```text
load8    0x4ab0    rA = zero_extend_u8([rB])
load16   0x4ab1    rA = zero_extend_le_u16([rB])
load32   0x4ab2    rA = le_u32([rB])
store8   0x5ab0    [rA] = low_u8(rB)
store16  0x5ab1    [rA] = low_le_u16(rB)
store32  0x5ab2    [rA] = le_u32(rB)
```

## Stack Pointer

`r15` is reserved as the stack pointer:

```text
r15  sp
```

The stack:

- lives in normal guest RAM;
- grows toward lower addresses;
- uses 4-byte slots in v1;
- is read and written with ordinary K16 load/store instructions.

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
`fp`, then using ordinary K16 load/store instructions. The helper epilogue
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

The current Rux compiler frame slice does not implement stack-passed helper
arguments, recursion, or return slots. User-space programs use the same
compiler-owned helper stack convention as other K16 targets, with process
ownership defined by the OS exec service rather than by helper call lowering.

## Target Stack Ownership

All K16 targets use the same stack convention, but the stack top is provided
by different layers:

```text
bios        firmware-owned temporary stack if needed
boot        BIOS or boot entry contract initializes boot stack top
kernel      bootloader passes or installs kernel stack top before entry
program     OS exec service supplies the process stack top for the loaded image
```

The current implementation only reserves the convention and proves the memory
behavior. Kernel/user privilege separation, virtual memory, and page-table
address translation are later ABI slices.

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
local storage. Stack load/store faults are normal K16 CPU faults. The first
ABI slice does not add stack bounds metadata, callee-cleaned arguments, or a
fallback return path.

## Traps And Interrupts

K16 uses one trap-vector path for synchronous CPU exceptions and asynchronous
interrupts. Firmware or kernel code installs the handler entry address through
`trap_vector`.

CSR instructions use the zero-opcode encoding family:

```text
read_csr   0x0ab2    rA = csr(B)
write_csr  0x0ab3    csr(A) = rB
iret       0x0004    restore interrupted frame; pc = trap_pc; r0 = handler result
wait       0x0006    report non-terminal wait signal to host
syscall    0x0a05    trap_cause = explicit trap; trap_value = rA; trap_pc = next pc
```

`halt` is the terminal guest execution stop. `wait` is a resumable host-visible
boundary for idle loops: the CPU reports a `wait` signal after advancing `pc`
and remains runnable, so the next host tick resumes at the following
instruction. Guest OS code should use `wait` for idle turns that have no work
until the host schedules the VM again, normally after input or timer progress.
`yield` remains the control-MMIO/syscall cooperative pause mechanism.

The active v1 CSRs are:

```text
csr  access  name               semantics
1    R/W     trap_vector        handler entry PC, 0 means no handler installed
2    R       trap_cause         last trap or interrupt cause
3    R       trap_pc            interrupted or faulting PC
4    R       trap_value         cause-specific diagnostic value
5    R/W     interrupt_enable   0 disables async interrupt delivery, non-zero enables it
6    R/W     interrupt_mask     enabled interrupt source bitmask
7    R       interrupt_pending  pending interrupt source bitmask
8    R       trap_arg0          first captured syscall argument, or exception address mode
9    R       trap_arg1          second captured syscall argument, or exception privilege mode
10   R       trap_arg2          third captured syscall argument, or 0 otherwise
11   R/W     trap_frame_index   selected saved trap register index, 0..15
12   R/W     trap_frame_register selected saved trap register value
13   R/W     trap_resume_pc     `iret` resume PC
14   R/W     trap_stack_pointer `iret` resume stack pointer
15   R/W     trap_interrupt_enable `iret` restored interrupt-enable state
```

Writes to read-only CSRs raise an explicit synchronous trap. Writing a
`trap_frame_index` outside `0..15` also raises an explicit synchronous trap.

Synchronous exceptions are delivered immediately when the faulting instruction
is decoded or executed. If `trap_vector = 0`, the VM reports a hard CPU trap to
the host. Otherwise the CPU records `trap_cause`, `trap_pc`, and `trap_value`,
then saves the interrupted register frame, stack pointer, interrupt state,
address mode, and privilege mode. For non-syscall exceptions, `trap_arg0`
records the interrupted address mode (`0 = physical`, `1 = translated`) and
`trap_arg1` records the interrupted privilege mode (`0 = kernel`, `1 = user`);
`trap_arg2` is zero. Trap entry then switches execution to physical/kernel mode
and sets `pc = trap_vector`, so a translated user program enters a physical-mode
kernel handler. If the trap interrupted translated user execution, the live
stack pointer is also switched to the kernel stack pointer captured when that
user address space was activated; `trap_stack_pointer` continues to hold the
interrupted user stack pointer for `iret`.

The `syscall rA` instruction is the returning explicit-trap entry for guest
OS services. It records `trap_cause = 0x00000005`,
`trap_value = rA`, and `trap_pc` as the next instruction after `syscall`, then
records the current stack pointer as `trap_stack_pointer` and enters
`trap_vector` in physical/kernel mode with interrupt delivery disabled until
`iret`. For translated user callers, the handler starts on the saved kernel
stack rather than the interrupted user stack. A kernel handler can complete the
service and use `iret` to resume the caller after the `syscall` instruction.

The initial K16 syscall ABI v0 is a guest runtime convention layered on this
CPU instruction. The CPU does not decode syscall tables. Runtime helper code
places the syscall number in `r1`, arguments in `r2`, `r3`, and `r4`, executes
`syscall r1`, and receives the kernel result from `r0` after `iret`. The
host-generated `__k16_syscall0`, `__k16_syscall1`, and `__k16_syscall3` helper
symbols implement those generic call shapes for low-level runtime objects, but
`k16-rt` no longer exposes them as public Rust APIs. At the `syscall r1`
boundary the CPU captures `r2`, `r3`, and `r4` into `trap_arg0`, `trap_arg1`,
and `trap_arg2` before entering the kernel. The kernel interprets `trap_value`
as the syscall number, reads the captured `trap_arg*` CSRs for arguments, may
freely use registers while running in the
trap vector, and returns a `u32` result by placing it in `r0` before `iret`.
`iret` restores the interrupted user register frame for `r1..r15`; the current
handler `r0` becomes the caller-visible return value. `iret` also restores the
interrupted address mode and privilege mode, so a syscall from translated user
execution returns to translated user execution. After `iret`, pending interrupt
delivery is deferred for two resumed guest instructions so helper code can
return to the caller and the caller can consume or save `r0` before an
asynchronous interrupt can enter the trap vector.

Kernel code can rewrite the saved trap frame before `iret`. `trap_resume_pc`,
`trap_stack_pointer`, and `trap_interrupt_enable` directly control the
corresponding `iret` restore fields. `trap_frame_index` selects a saved register
slot and `trap_frame_register` reads or writes that slot. This selector pair is
used instead of assigning one CSR per register because the v1 CSR instruction
encoding has a 4-bit CSR number field.

K16 syscall ABI v0 names the current Rust-kernel proof services in
`k16_abi::syscall`:

| Constant | Number / value | ABI call shape | Meaning |
| --- | ---: | --- | --- |
| `DEBUG_MARKER` | `2` | `syscall0(DEBUG_MARKER)` | Kernel writes `S` to debug output and returns `DEBUG_MARKER_RETURN`. |
| `DEBUG_WRITE_BYTE` | `3` | `syscall1(DEBUG_WRITE_BYTE, byte)` | Kernel writes the low byte supplied in `trap_arg0` and returns `STATUS_OK`. |
| `YIELD` | `4` | `syscall0(YIELD)` | Kernel yields once to the host and then returns `STATUS_OK`. |
| `SLEEP_TICKS` | `5` | `syscall1(SLEEP_TICKS, ticks)` | Kernel waits until `timer0.game_ticks` advances by `ticks`, then returns `STATUS_OK`. |
| `EXIT` | `6` | `syscall1(EXIT, status)` | Terminates the current process. If the process has a blocked parent, the status is returned to that parent; if init exits, the kernel halts the VM with the supplied status. |
| `WRITE` | `7` | `syscall3(WRITE, fd, ptr, len)` | Writes bytes from guest memory to fd `1`, fd `2`, or a write-only regular file fd; regular files advance their descriptor offset and update KFS inode size. Returns byte count or a negative K16 error. |
| `READ` | `8` | `syscall3(READ, fd, ptr, len)` | Reads bytes from fd `0` or an open regular file fd into guest memory; stdin blocks by waiting until input is available, regular files advance their descriptor offset, and the syscall returns byte count or a negative K16 error. |
| `RUN` | `9` | `syscall3(RUN, request, len, format)` | Synchronously loads a dynamic `/bin/*.kx` user program from `ROOT`/KFS on `storage0`, blocks the current foreground process while the child runs, and returns either a non-negative child exit status or a negative K16 error. Child status `0` is success; non-zero child statuses are successful launches whose child process reported failure. Negative values are launch/runtime errors such as missing executable, invalid executable, no memory, busy child slot, or translated user fault. `trap_arg2 = 1` means `trap_arg0/trap_arg1` point to a bounded argv request block beginning with `RUN_ARGV_MAGIC`; `argc = 0` is valid for zero-argument launches. Other `trap_arg2` formats are invalid. |
| `OPEN` | `10` | `syscall3(OPEN, path, len, flags)` | Opens an absolute ROOT/KFS regular file path from `storage0`. `flags = OPEN_READ_ONLY` opens an existing file for read. `flags = OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_TRUNCATE` creates or truncates a regular file for write. `flags = OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_APPEND` creates a missing regular file or opens an existing regular file with its descriptor offset at EOF. Success returns a regular file fd starting at `3`. |
| `CLOSE` | `11` | `syscall1(CLOSE, fd)` | Closes a regular file fd owned by the current foreground process. Standard descriptors `0..=2` are not closeable. |
| `BRK` | `12` | `syscall1(BRK, addr)` | Sets the current foreground process program break to `addr` and returns the resulting break, or a negative K16 error. The break must stay inside the kernel-selected heap arena for that process. For translated processes, growing the break commits any newly crossed heap VM pages through the kernel page allocator. |
| `SBRK` | `13` | `syscall1(SBRK, delta)` | Grows the current foreground process program break by `delta` bytes and returns the previous break, or a negative K16 error. For translated processes, newly committed heap pages are mapped user-writable and non-executable. |
| `READ_DIR` | `14` | `syscall3(READ_DIR, request, len, 0)` | Reads a ROOT/KFS directory listing from `storage0` into a caller-provided buffer. The request is `u32 magic`, `u32 path_len`, `u32 out_ptr`, `u32 out_len`, followed by path bytes. The output buffer receives packed little-endian records: `u32 file_type`, `u32 name_len`, `name_len` raw name bytes, `u32 size_bytes`. |
| `STAT` | `15` | `syscall3(STAT, path, len, metadata)` | Reads ROOT/KFS path metadata from `storage0` into a 16-byte response buffer. Word 0 is `FILE_TYPE_REGULAR` or `FILE_TYPE_DIRECTORY`; word 1 is `size_bytes`; words 2 and 3 are reserved zero. |
| `GAME_TICKS` | `16` | `syscall1(GAME_TICKS, out)` | Copies the current `timer0.game_ticks` value into an 8-byte user buffer as little-endian `{ low, high }` words and returns `STATUS_OK`, or returns a negative K16 error. |
| `SEEK` | `17` | `syscall3(SEEK, fd, offset, whence)` | Sets a regular file descriptor offset and returns the new offset. `SEEK_SET` accepts offsets inside the current file size. `SEEK_END` currently accepts only offset `0` and returns EOF. Standard descriptors are not seekable. |
| `UNLINK` | `18` | `syscall3(UNLINK, path, len, 0)` | Removes an absolute ROOT/KFS regular file path from `storage0`. Directories are rejected, and files with an open kernel fd are rejected with `ERROR_BUSY`. Returns `STATUS_OK` or a negative K16 error. |
| `MKDIR` | `19` | `syscall3(MKDIR, path, len, 0)` | Creates an absolute ROOT/KFS directory path on `storage0`. Parent directories must already exist. Returns `STATUS_OK` or a negative K16 error. |
| `RMDIR` | `20` | `syscall3(RMDIR, path, len, 0)` | Removes an empty absolute ROOT/KFS directory path from `storage0`. Regular files are rejected, and non-empty directories return `ERROR_NOT_EMPTY`. Returns `STATUS_OK` or a negative K16 error. |
| `RENAME` | `21` | `syscall3(RENAME, request, len, 0)` | Renames an absolute ROOT/KFS regular file path on `storage0` without changing its inode or contents. The request is `u32 magic`, `u32 old_path_len`, `u32 new_path_len`, followed by old path bytes and new path bytes. Directories are rejected, existing destination paths return `ERROR_INVALID`, and source files with an open kernel fd return `ERROR_BUSY`. |
| `DEBUG_MARKER_RETURN` | `0x53` | n/a | Proof return value for `DEBUG_MARKER`. |
| `STATUS_OK` | `0` | n/a | Successful proof-service status. |
| `FILE_TYPE_REGULAR` | `1` | n/a | `STAT` response kind for a regular file. |
| `FILE_TYPE_DIRECTORY` | `2` | n/a | `STAT` response kind for a directory. |
| `OPEN_READ_ONLY` | `0` | n/a | `OPEN` flags value for existing read-only regular files. |
| `OPEN_WRITE_ONLY` | `1` | n/a | `OPEN` flag bit for write-only regular file descriptors. |
| `OPEN_CREATE` | `2` | n/a | `OPEN` flag bit that creates the file when missing in the supported write-create-truncate mode. |
| `OPEN_TRUNCATE` | `4` | n/a | `OPEN` flag bit that truncates the file in the supported write-create-truncate mode. |
| `OPEN_APPEND` | `8` | n/a | `OPEN` flag bit for the supported write-create-append mode; the opened descriptor starts at EOF. |
| `SEEK_SET` | `0` | n/a | `SEEK` mode for an absolute file offset. |
| `SEEK_END` | `2` | n/a | `SEEK` mode for EOF-relative seek; K16 currently supports only `offset = 0`. |
| `RENAME_REQUEST_MAGIC` | `0x4d41_4e52` | n/a | Little-endian `RNAM` marker for `RENAME` request buffers. |
| `MAX_RENAME_PATH_BYTES` | `228` | n/a | Maximum old or new path byte length accepted by `RENAME`. |
| `MAX_RENAME_REQUEST_BYTES` | `468` | n/a | Maximum bounded `RENAME` request size: 12-byte header plus two maximum paths. |
| `FD_STDIN` | `0` | n/a | Standard input descriptor accepted by `READ`. |
| `FD_STDOUT` | `1` | n/a | Standard output descriptor accepted by `WRITE`. |
| `FD_STDERR` | `2` | n/a | Standard error descriptor accepted by `WRITE`. |
| `ERROR_BAD_FD` | `0xffff_fff7` | n/a | Negative K16 error value corresponding to POSIX-aware `EBADF` semantics. |
| `ERROR_BUSY` | `0xffff_fff0` | n/a | Negative K16 error value corresponding to POSIX-aware `EBUSY` semantics. |
| `ERROR_EXEC_FORMAT` | `0xffff_fff8` | n/a | Negative K16 error value corresponding to POSIX-aware `ENOEXEC` semantics. |
| `ERROR_FAULT` | `0xffff_fff2` | n/a | Negative K16 error value corresponding to POSIX-aware `EFAULT` semantics. |
| `ERROR_INVALID` | `0xffff_ffea` | n/a | Negative K16 error value corresponding to POSIX-aware `EINVAL` semantics. |
| `ERROR_NO_ENTRY` | `0xffff_fffe` | n/a | Negative K16 error value corresponding to POSIX-aware `ENOENT` semantics. |
| `ERROR_NO_FD` | `0xffff_ffe8` | n/a | Negative K16 error value used when no regular file descriptor slot is available. |
| `ERROR_NO_MEMORY` | `0xffff_fff4` | n/a | Negative K16 error value corresponding to POSIX-aware `ENOMEM` semantics. |
| `ERROR_NOT_EMPTY` | `0xffff_ffef` | n/a | Negative K16 error value used when `RMDIR` rejects a directory with live entries. |

ROOT/KFS mutating syscalls (`OPEN` create/truncate, file `WRITE`,
`UNLINK`, `MKDIR`, `RMDIR`, and `RENAME`) use write-through persistence:
after the filesystem blocks are updated, the kernel issues `storage0 flush`
before returning success. A flush failure is reported as a negative storage
error; already-written filesystem blocks are not rolled back.

These names describe the current ABI proof surface. They are not a complete OS
service table, scheduler API, filesystem API, or process model.

`RUN`, `EXIT`, `BRK`, and `SBRK` operate on a bounded cooperative foreground
process model. The kernel keeps a fixed process table for init, shell, and one
nested utility. `RUN` saves the current process trap frame, marks that process
blocked on its child, loads the child in a kernel-selected arena, and enters the
child. `EXIT` clears the current child slot and restores the waiting parent
trap frame with the child status in `r0`. There is no background scheduling,
preemption, fork, or pipe in this model.

Each process table entry also records a kernel-owned PID and optional parent
PID. Init is PID 1, and child launches allocate monotonic non-slot PIDs starting
at 2, so a reused foreground slot does not reuse its prior PID. These PIDs are
not exposed by the current syscall ABI: `RUN` remains synchronous and returns
child exit status or a negative K16 error, not a PID. The PID metadata is
reserved for future process APIs such as `SPAWN`/`WAIT`.

The internal process-table model also reserves an exited-child reap state for
future `WAIT`: an exited child can retain its PID, parent PID, and exit status
until its parent reaps either that exact PID or `0 = any direct child`. This is
not a guest-visible syscall in v1. The current synchronous `RUN` path still
auto-reaps the child before returning the child status to the caller.

Each foreground process records an explicit guest-physical memory range
`memory_start..memory_end`. The init process range is derived from the loaded
init image and the profile v2 `BootInfo.ram_size` value preserved by the boot
chain. A child process range is derived from the kernel-selected child load
base and stack top. Syscalls that consume user pointers validate buffers
against the current foreground process range rather than a global hard-coded
user window.

This is now a translated user-process model with a remaining physical pointer
path for kernel tests and physical processes. If the current process has no
address-space id, the kernel treats syscall pointers as
guest-physical addresses, validates them against that process range, and copies
bytes directly from or to physical RAM. If the current process has an
address-space id, the kernel treats syscall pointers as user virtual addresses
and moves bytes through the privileged `mmu0` `copy_from_user` and
`copy_to_user` commands. `mmu0` status errors, short copy results, invalid
physical process ranges, and arithmetic overflow are reported to userland as
the existing negative K16 `ERROR_FAULT` value.

The production process launcher runs `/bin/init.kx`, `/bin/shell.kx`, and
shell-launched `/bin/*.kx` utilities in host-managed `mmu0` address spaces.
The kernel maps the loaded image pages and selected committed user stack pages
into each address space, activates translated user execution, and records the
address-space id for syscall user-buffer copies. For init, the kernel reserves
the top 4 KiB page below the boot-provided `BootInfo.ram_size` boundary as a
physical kernel trap stack and places init's user stack below that page. Init's
lower user arena boundary is not a fixed window: it is the next 4 KiB page
after the maximum of `BootInfo.program_base`, the storage loader scratch range,
the linked kernel image end, and kernel terminal state. For `RUN` children, the
kernel reserves a physical kernel trap stack below the saved parent stack
before placing the child stack. Before child activation, the kernel restores
the child's saved trap register frame; this preserves entry registers such as
`argc` in `r1` and `argv` in `r2` while keeping later translated-user traps off
the child user stack. When a translated child exits to a translated parent, the
kernel uses `mmu0` `set_trap_return_address_space` with the parent
address-space id and parent physical kernel trap stack before `iret`, so the
saved parent frame resumes in translated/user mode. The kernel destroys the
exiting translated child's `mmu0` address space before returning to the parent.

Each foreground process has its own monotonic heap after its loaded image. A
child load arena starts after the current parent's program break, so child
loading cannot overwrite parent heap allocations. The child arena end is the
lower of the current parent's saved stack pointer and `memory_end`; translated
children reserve a kernel trap stack below that parent stack before placing the
child stack and heap. Translated user launches reserve two 4 KiB user stack
pages below the selected stack top and keep a 4 KiB guard below that stack
range. The heap limit is below the guard, so child heap growth cannot overwrite
the live parent stack, the translated child trap stack, or escape the parent's
process range. For translated processes, the image page containing the initial
heap start is already mapped by the loader. Later `BRK`/`SBRK` growth commits
additional heap pages lazily at 4 KiB boundaries, maps them user-writable and
non-executable, and releases the heap backing frames when the process exits.

Regular file descriptors returned by `OPEN` are process-owned. A foreground
process can `READ` or `CLOSE` only regular fds it opened, and `EXIT` releases
only the exiting process's regular fds before resuming the waiting parent. There
is no fd inheritance across `RUN` in this model. Stdio descriptors `0`, `1`,
and `2` remain a shared kernel convention and are not stored in the regular fd
table.

For argv launches, the K16 ABI copies up to four bounded argument byte strings
into the child arena after the loaded image and before the child heap. The
request block is `u32 magic`, `u32 path_len`, `u32 argc`, `u32 arg_len[argc]`,
followed by the path bytes and each argument byte string in order. `argc` must
be in `1..=4`, each argument is limited to 128 bytes, and the path is limited
to 61 bytes. On child entry, `r1` contains `argc` and `r2` contains a pointer to
an argv table of `(ptr, len)` entries. The K16 startup object does not clobber
`r1` or `r2` before calling `main`, so no-argument `main()` programs remain
valid while argv-aware programs may declare an entry that accepts those first
two C ABI arguments.

Bounded syscall request buffers may live on the translated user stack when
their maximum size is part of the ABI and remains comfortably below the
committed stack range. The current large request builders are `READ_DIR` at
244 bytes, `RENAME` at 468 bytes, and `RUN_ARGV` at 601 bytes. Larger future
request formats should use heap-backed user buffers or a narrower syscall ABI
instead of relying on more stack growth. Kernel-side syscall staging buffers
remain separately bounded and are not part of the user stack budget.

Asynchronous interrupts are delivered between guest instructions. Delivery
requires `interrupt_enable != 0` and a source bit present in both
`interrupt_pending` and `interrupt_mask`. Entering an interrupt records the
interrupted `pc`, interrupted stack pointer, and cause/value, clears the
delivered pending bit, sets `pc = trap_vector`, and disables global interrupt
delivery. `iret` restores the interrupted user register frame for `r1..r15`,
uses the handler's current `r0` as the resumed `r0`, restores the interrupted
stack pointer, resumes at `trap_pc`, and restores the saved interrupt-enable
state. If another interrupt is already pending when `iret` returns, delivery is
deferred until after two resumed guest instructions. Nested interrupts,
interrupt priorities, and separate
interrupt-controller hardware are not part of this ABI slice.

Current trap and interrupt causes:

```text
0x00000001  illegal instruction
0x00000002  instruction fetch fault
0x00000003  load fault
0x00000004  store fault
0x00000005  explicit trap
0x80000001  timer0 interrupt
0x80000002  keyboard0 interrupt
```

The first K16 kernel handles translated user instruction-fetch, load, and store
faults by exiting the current foreground child with `ERROR_FAULT` and resuming
the waiting parent through the normal `EXIT` cleanup path. Kernel-mode unknown
synchronous traps remain kernel panics. Illegal instructions are not yet a
user-recoverable status.

Current interrupt source bits:

```text
bit 0 / mask 0x00000001  timer0 game tick
bit 1 / mask 0x00000002  keyboard0 input available
```

## Compiler-Generated Register Saves

When the K16 compiler emits a real helper call and the caller has live local
registers, it saves those local registers to the `r15` stack before `call` and
restores them after `ret`.

Compiler backends must not use `r12` or `r15` as scratch or local registers.
This is a compiler-owned preservation rule for the current register-backed
local lowering. It is narrower than the initial LLVM-facing target model above:
the current Rux compiler save path does not yet implement stack-passed
parameters, return slots, or a general-purpose external calling convention.
