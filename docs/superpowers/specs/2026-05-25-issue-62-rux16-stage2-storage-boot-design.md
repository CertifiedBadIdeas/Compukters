# Rux16 Stage2 Storage Boot Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Context

Rux16 BIOS can now start from read-only BIOS flash and read block 0 from `storage0` into guest RAM. The next step is to turn that data path into an actual boot chain: BIOS reads a small boot header, loads a second-stage Rux16 program from storage into RAM, and jumps to the RAM entry point.

## Goal

Add a native test boot protocol where Rux16 BIOS validates a raw boot block, copies one stage2 Rux16 block from `storage0` into guest RAM, and transfers execution to the loaded program with a normal Rux16 jump.

## Boot Block Format

Block 0 is a raw little-endian boot block:

```text
offset  size  field
0x00    4     magic = "RUXB"
0x04    4     entry_pc
0x08    4     load_addr
0x0C    4     block_count
0x10    4     start_lba
```

For this slice, `block_count` must be `1`. This keeps BIOS arithmetic simple until a richer firmware assembler or more Rux16 instructions exist. The format is intentionally raw and independent of the future filesystem/partition-table work.

## Architecture

BIOS runs from flash at reset, reads block 0 into a RAM header buffer, validates the magic, reads the configured stage2 block into `load_addr`, then jumps to `entry_pc`. The loaded stage2 program is ordinary Rux16 instruction bytes in RAM; after the jump there is no host-side decode and no `LowImage` transition.

Invalid magic is a deterministic BIOS error: BIOS writes a nonzero marker to control MMIO and halts. The first positive slice validates the successful path; corrupt-header coverage can be added next without changing the boot contract.

## Out of Scope

- Multi-block stage2 loading.
- Filesystem, partition table, or volume metadata lookup.
- Host-side RUXI decode/start handoff.
- Any fallback path to `LowImage` or bundled firmware.
- In-game wiring for prepared storage media.

## Verification

- Native Rust test: BIOS loads stage2 from `storage0` block 1 into RAM and stage2 writes an observable debug/control marker.
- Regression: existing Rux16 BIOS flash/storage tests continue to pass.
