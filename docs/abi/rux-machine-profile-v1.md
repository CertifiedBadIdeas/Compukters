# Rux Machine Profile v1

## Status

Status: pre-freeze candidate.

This document defines the baseline computer-class machine environment that runs `RUXI` low images. It is separate from the image ABI so compiler frontends can target the binary image format without depending on a particular device map.

## Scope

The image ABI defines bytes on disk. The machine profile defines what those bytes run on:

- linear RAM size and initialization;
- memory bus behavior;
- scalar halt signals;
- implementation-defined runtime budgets;
- future MMIO/device address ranges.

## Memory

The baseline machine has one byte-addressed linear RAM space.

At VM creation time, RAM is initialized from the image sections:

```text
0                                      rodata.len
| rodata bytes                         |
                                       data.len
                                       | data bytes |
                                                    bss_size
                                                    | zero bytes |
                                                               rest of RAM
                                                               | zero bytes |
```

Loads and stores use 32-bit byte addresses. Multi-byte loads and stores are little-endian and do not require alignment.

The baseline profile rejects a zero-byte RAM allocation. An image `memory_size` that is smaller than `rodata.len + data.len + bss_size` is invalid.

## Shared RAM

Computer-class runtimes may run multiple CPU contexts against the same RAM object. This is a machine/runtime choice, not an image ABI difference. The image still encodes its own initial memory sections; a host that uses shared RAM decides when and how those sections are applied.

This lets the Rust daemon model a computer as one machine with shared memory while still allowing standalone fixture execution.

## MMIO

No stable MMIO range is frozen in machine profile v1 yet.

Until this document assigns concrete address ranges, all addresses are interpreted as RAM by the baseline memory implementation. Device-backed memory buses may exist experimentally, but external compilers should not rely on them for v1 compatibility.

When MMIO is stabilized, it should be added to a new machine profile version unless the current profile is still pre-freeze.

## Halt Signals

The baseline runner reports these terminal states:

- `HaltUnit`;
- `HaltI32(value)`;
- `HaltI64(value)`;
- `HaltAddr(value)`;
- `HaltBool(value)`;
- `Pause`;
- runtime error.

`Pause` means the VM exhausted its current scheduling budget and can be resumed later with the same machine state.

## Runtime Budgets

The image ABI does not serialize scheduling budgets. A host chooses:

- time-slice duration;
- optional instruction check interval;
- call-depth limit;
- memory quota for a machine;
- number of CPU contexts attached to shared RAM.

These values are implementation-defined machine/runtime parameters. They must not change image decode semantics.
