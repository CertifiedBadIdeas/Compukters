# Rux16 Guest Instruction-Memory CPU Design

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

## Status

Accepted design direction; implementation plan not written yet.

## Context

The current native Rux VM executes host-decoded `RUXI` images:

```text
RUXI bytes
  -> host decode_image(...)
  -> Image / LowProgram
  -> LowCpuContext executes decoded operations
```

This model is convenient for compiler/runtime tests and bundled firmware, but it
does not match the machine model we want for BIOS, storage boot, and a future
OS. Guest code can read executable bytes from `storage0` into guest RAM, yet it
still cannot execute those bytes without a host-side decode/start service.

#62 introduced a RAM-buffer boot handoff as a temporary bridge: BIOS reads bytes
from storage into RAM, then the host decodes those bytes and replaces the BIOS
CPU. #68 used the same idea for future OS `exec`. That bridge keeps storage and
path policy inside the guest, but it still makes execution depend on the host.

The new direction is to make decode part of CPU execution. Instruction bytes
should live in guest-visible memory, and the CPU should fetch, decode, and
execute them from a program counter.

## Goal

Create a new guest instruction-memory CPU substrate, tentatively named `Rux16`,
that can execute instruction bytes stored in guest memory.

The first useful end state is:

```text
host
  -> creates ComputerMachine
  -> maps RAM/MMIO/storage/display/input
  -> places BIOS bytes in ROM or RAM
  -> starts Rux16 CPU at bios_entry

BIOS
  -> reads storage0 through MMIO
  -> copies boot image bytes into RAM
  -> validates boot metadata
  -> jumps to loaded entry address
```

There is still instruction decode, but decode belongs to the guest CPU execution
loop, not to an external host service.

## Architecture

`Rux16` runs directly against `MachineBus`:

```text
Rux16CpuState
  pc: u32
  registers: [u32; 16]
  halted/trap state
  metrics

MachineBus
  RAM
  MMIO devices

execution step
  fetch u16 instruction word at pc
  decode instruction family and fields
  read/write registers and MachineBus
  update pc
```

The CPU uses a byte-addressed memory model. Instruction words are 16-bit
little-endian values loaded from `pc`. The normal instruction length is one
word, so normal flow advances `pc` by 2. Some instruction families consume one
or more extension words.

The source of truth is guest memory. An implementation may later cache decoded
instructions for speed, but the cache must behave as an optimization over
memory bytes, not as the architectural program representation.

## Instruction Encoding

The base instruction word is 16 bits:

```text
bits 15..12  op
bits 11..8   a
bits 7..4    b
bits 3..0    c / subop / small immediate
```

The top-level `op` field defines instruction families, not every concrete
operation. Sixteen top-level values are enough if they are treated as pages.

Initial families:

```text
0x0  system      nop, halt, trap
0x1  move/const  mov, const small, lui
0x2  alu         add, sub, and, or, xor, shifts through subop
0x3  compare     eq, lt, test through subop
0x4  load        load8/load16/load32 through subop
0x5  store       store8/store16/store32 through subop
0x6  branch      relative conditional branches
0x7  jump        register jump and short relative jump
0x8  call        call register / relative call
0x9  ret         return
0xA  reserved    future stack/frame helper page
0xB  reserved    future process/syscall/device page
0xC  reserved
0xD  reserved
0xE  ext         extended/wide instruction prefix
0xF  illegal     guaranteed trap for invalid/unallocated encoding
```

The exact subop layout is intentionally deferred to the implementation plan. The
constraint for that plan is fixed here: common BIOS code must fit in compact
16-bit instructions, while wide constants and absolute addresses use extension
words.

Example wide constant sequence:

```text
lui   r1, 0x1000
ori16 r1, 0x0040
jmp   r1
```

This keeps the base ISA small without limiting the address space to 16 bits.

## Registers And Data Model

Initial register file:

```text
16 general-purpose registers
each register is u32
```

Register `r0` can remain a normal register in the first slice. If zero-register
semantics become useful later, that should be a deliberate ISA decision rather
than an accidental constraint.

The first implementation should focus on `u32`/address behavior. Smaller memory
access widths are handled by load/store variants:

```text
load8
load16
load32
store8
store16
store32
```

Arithmetic overflow should use wrapping two's-complement behavior unless an
instruction explicitly traps.

## Control Flow

`pc` is a guest address. Normal instructions advance by 2 bytes. Branches,
jumps, calls, and returns explicitly set `pc`.

Minimum control-flow requirements:

- short relative branch for compact loops and conditionals;
- register jump for bootloader handoff to a loaded entry address;
- call/ret for firmware subroutines;
- halt/trap state for deterministic tests and firmware error reporting.

The BIOS handoff model becomes a normal guest operation:

```text
read boot image into RAM
compute entry address
jmp entry_register
```

No host-side CPU replacement is required for the final architecture.

## Memory And MMIO

Instruction fetch and data load/store should both go through the same
`MachineBus` boundary unless the implementation needs an explicit execute
permission model later.

This keeps existing devices useful:

- `BootInfo` and `HardwareTable` remain RAM structures;
- `storage0` remains an MMIO block device;
- display/debug/control/input remain MMIO devices;
- BIOS and OS code can use the same load/store instructions to talk to devices.

The first slice does not need memory protection, virtual memory, execute-only
pages, or user/kernel isolation.

## Relationship To Current LowImageVm

`LowImageVm` should remain available while `Rux16` is introduced. The migration
should be parallel, not a destructive rewrite:

```text
current path:
  RUXI -> host Image/LowProgram -> LowCpuContext

new path:
  Rux16 bytes in MachineMemory -> fetch/decode/execute by pc
```

The existing `RUXI` ABI can continue to serve compiler/runtime tests and
compatibility while the new guest-executable substrate matures.

New code should not extend host-side boot/exec handoff unless it is needed as a
short-lived bridge. The desired future for #62 and #68 is guest execution:

```text
#62 boot:
  BIOS reads boot bytes -> BIOS jumps to entry

#68 exec:
  OS reads executable bytes -> OS maps/copies bytes -> OS starts/jumps/schedules guest code
```

## Boot And Exec Impact

#62 should evolve from "BIOS asks host to decode RUXI from RAM" to "BIOS loads a
guest-executable image and jumps to its entry address".

#68 should evolve from "OS asks VM to spawn decoded RUXI bytes" to "OS loader
maps executable bytes into guest memory and creates/schedules guest CPU/process
state".

The boot record from #62 can still remain useful. It should eventually point to
a guest-executable image rather than a host-decoded `RUXI` payload.

## First Implementation Slice

The first implementation should be deliberately small:

- add a new `rux16` module in `native/rux-vm`;
- define `Rux16CpuState`;
- implement fetch of `u16` little-endian instruction words from `MachineBus`;
- implement `halt`, small constants, `add`, `load32`, `store32`, and register
  jump;
- run a tiny program stored as raw bytes in `MachineMemory`;
- prove MMIO routing still works through `MachineBus`.

This slice does not need compiler support. Tests can hand-encode instruction
words until the ISA shape stabilizes.

## Testing Strategy

Native Rust tests should cover:

- fetch/decode of little-endian 16-bit words from guest memory;
- `pc` advancing by 2 for normal instructions;
- extension-word consumption for at least one wide constant instruction;
- arithmetic and register moves;
- load/store through regular RAM;
- load/store through a small test MMIO device on `MachineBus`;
- register jump to an address loaded from guest memory;
- halt/trap behavior;
- existing `LowImageVm` tests remaining green.

## Open Follow-Ups

- Define the exact subop layout for each instruction family.
- Decide whether `r0` should become a zero register.
- Decide whether BIOS bytes live in ROM, protected RAM, or regular RAM in the
  first Rux16 machine profile.
- Define a guest-executable container format after the raw instruction-memory
  CPU exists.
- Update #62 and #68 once the Rux16 substrate is implemented enough to replace
  host-side decode/start.
