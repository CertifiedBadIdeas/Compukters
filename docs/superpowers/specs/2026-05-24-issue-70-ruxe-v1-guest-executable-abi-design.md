# RUXE v1 Guest Executable ABI Design

> Issue: [#70](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/70)

## Status

Accepted design direction; implementation plan not written yet.

## Context

`RUXI v1` is the current host-decoded image ABI:

```text
RUXI bytes
  -> host decode_image(...)
  -> Image / LowProgram
  -> LowCpuContext
```

That format is useful for compatibility, compiler tests, and existing firmware
work, but it is structurally the wrong boot artifact for guest instruction
memory. #69 introduces the new direction: Rux code should live as bytes in guest
memory, and the CPU should fetch, decode, and execute those bytes from `pc`.

That requires a new executable ABI:

```text
RUXE file bytes
  -> BIOS/OS loader validates header
  -> loader copies sections into guest memory
  -> loader zero-fills bss-like sections
  -> loader jumps to entry address
```

`RUXI v1` should remain frozen as a host-decoded compatibility artifact. `RUXE
v1` becomes the future guest-loadable boot/exec container.

## Goal

Define `RUXE v1`, a little-endian guest executable container that BIOS and a
future OS can load without asking the host to decode or start the program.

## Relationship To RUXI And Rux16

The formats have different jobs:

```text
RUXI v1
  host-decoded image format
  compatibility and current tests
  not the future boot/exec artifact

Rux16
  guest instruction set and encoding from #69
  bytes are fetched/decode/executed by the CPU

RUXE v1
  guest-loadable executable container
  wraps Rux16 code/data sections
  tells BIOS/OS where to copy bytes and where to jump
```

`RUXE v1` should identify the contained ISA explicitly. The first supported ISA
is `rux16`, but the container should leave room for a future `rux32` or another
guest-executable encoding.

## File Layout

All multi-byte fields are little-endian. The file begins with a fixed header,
followed by a section table, followed by section payload bytes.

```text
RuxeHeader
SectionHeader[section_count]
payload bytes
```

The first version should use fixed-size headers so BIOS can parse it with small
code and no dynamic schema.

## Header

Proposed fixed header:

```text
offset  size  name
0x00    4     magic = "RUXE"
0x04    2     version = 1
0x06    2     header_size
0x08    4     flags
0x0C    4     isa
0x10    4     entry_addr
0x14    4     section_table_offset
0x18    2     section_count
0x1A    2     section_header_size
0x1C    4     reserved0
0x20    4     image_crc32
0x24    4     reserved1
```

Header size is 40 bytes. `header_size` allows future compatible extension while
letting v1 loaders skip unknown trailing header bytes.

`isa` is a four-byte tag:

```text
"R16\0"  Rux16 instruction-memory payload
"R32\0"  reserved for possible Rux32 payload
```

`entry_addr` is the guest memory address where execution starts after all
sections are loaded.

`flags` must be zero in v1. Unknown flag bits are rejected.

`reserved0` and `reserved1` must be zero.

`image_crc32` covers the whole file with this field treated as zero. A loader
may reject `image_crc32 == 0` unless the boot policy explicitly allows unchecked
developer images.

## Section Header

Proposed fixed section header:

```text
offset  size  name
0x00    2     kind
0x02    2     flags
0x04    4     load_addr
0x08    4     file_offset
0x0C    4     file_size
0x10    4     mem_size
0x14    4     align
0x18    4     reserved
```

Section header size is 28 bytes.

Section kinds:

```text
0  null      ignored
1  code      executable instruction bytes
2  rodata    read-only initialized data
3  data      writable initialized data
4  bss       zero-filled memory; file_size must be 0
```

Section flags:

```text
bit 0  readable
bit 1  writable
bit 2  executable
```

The first implementation does not need memory protection. Flags are still part
of the ABI so future tooling, validation, and disassembly know the intended
section role.

## Loader Responsibilities

The loader is guest code: BIOS for boot, OS for post-boot exec.

Loader algorithm:

```text
read RUXE bytes into a staging buffer
validate fixed header
validate section table bounds
for each section:
  validate file range
  validate mem_size >= file_size
  validate load_addr + mem_size does not overflow
  validate section load ranges do not overlap unless explicitly allowed
  copy file_size bytes from staging buffer + file_offset to load_addr
  zero-fill mem_size - file_size bytes after copied payload
jump entry_addr
```

The host does not receive a file path, storage LBA, section table, or decoded
program. It only provides storage/MMIO/RAM devices. The guest loader owns file
format policy.

## Validation Rules

A v1 loader must reject:

- bad magic;
- unsupported version;
- unsupported `isa`;
- `header_size < 40`;
- `section_header_size < 28`;
- non-zero unknown header flags;
- non-zero reserved header fields;
- `section_count == 0`;
- `section_table_offset < header_size`;
- section table outside file bounds;
- section table size overflow;
- non-zero unknown section flags;
- non-zero reserved section fields;
- section payload outside file bounds;
- `mem_size < file_size`;
- `load_addr + mem_size` overflow;
- overlapping non-null section memory ranges;
- `bss` section with non-zero `file_size`;
- executable entry address outside a loaded executable section;
- checksum mismatch when checksum enforcement is enabled.

The loader should report deterministic firmware/OS errors instead of jumping to
an unchecked address.

## Boot Record Impact

#62 boot records should eventually point to `RUXE` payloads:

```text
boot record
  image_lba -> RUXE file bytes
  image_len -> exact RUXE file length
  image_crc32 -> RUXE bytes, or boot-record-level checksum policy
```

BIOS reads the RUXE payload from `storage0`, validates it, loads sections into
guest memory, and jumps to `entry_addr`.

This replaces the temporary host-side `boot_handoff_ruxi` model:

```text
temporary:
  BIOS reads RUXI -> host decodes/starts

future:
  BIOS reads RUXE -> BIOS loads sections -> Rux CPU jumps to entry
```

## OS Exec Impact

#68 should treat `RUXE` as the executable file format for filesystem-backed
programs:

```text
spawn("/bin/shell.ruxe")
  -> OS opens file
  -> OS reads RUXE
  -> OS validates and maps sections
  -> OS creates/schedules guest CPU/process state at entry
```

The OS still owns path lookup, permissions, stdio handles, and process policy.
`RUXE` only defines the executable container.

## Compatibility

`RUXI v1` remains available for:

- current `LowImageVm` tests;
- existing compiler backend tests;
- bundled firmware compatibility during the transition;
- development utilities that still emit host-decoded images.

`RUXE v1` is experimental until a storage boot path proves it. Tools should
include version/ISA checks and should not assume forward compatibility beyond
the v1 fields documented here.

## First Implementation Slice

The first implementation should not require a compiler backend.

Recommended slice:

- add a `ruxe` module in `native/rux-vm`;
- define `RuxeHeader` and `RuxeSectionHeader` parser structs;
- parse from `&[u8]` using structured little-endian reads;
- validate header and section table bounds;
- load sections into a `MachineMemory` or `MachineBus` in tests;
- verify invalid headers and overlapping ranges are rejected.

Rux16 execution from #69 can consume loaded bytes later.

## Testing Strategy

Native Rust tests should cover:

- valid minimal `RUXE` header with one code section;
- bad magic;
- unsupported version;
- unsupported ISA;
- section table out of bounds;
- payload out of bounds;
- overlapping load ranges;
- `bss` zero-fill;
- entry outside executable section;
- successful load into guest memory.

## Open Follow-Ups

- Decide the exact checksum policy for developer images.
- Decide whether boot records keep a separate image checksum once RUXE has its
  own checksum.
- Define a simple assembler/emitter for hand-written Rux16/RUXE fixtures.
- Update #62 after RUXE parsing exists.
- Update #68 after OS process loading exists.
