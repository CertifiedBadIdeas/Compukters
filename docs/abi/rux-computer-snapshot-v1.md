# Rux Computer Snapshot v1

## Status

Status: experimental.

`RUXSNAP` is the host-side snapshot container for `ComputerMachine` runtime
state. It is not a guest-visible disk format and it is not stored on
`storage0`. Its purpose is to support future persistence of a running computer
across host unload/load boundaries.

The current v1 slice records a versioned header, full RAM bytes, and fixed-size
Rux16 CPU continuation records. Device state is not part of this slice yet.

## File Layout

All integers are little-endian.

```text
offset  size  field
0x00    8     magic: "RUXSNAP\0"
0x08    2     version: 1
0x0A    2     header_size: 32
0x0C    4     flags: 0
0x10    8     ram_size
0x18    4     cpu_count
0x1C    4     boot_cpu_id, or 0xffffffff when absent
0x20    ...   RAM bytes, exactly ram_size bytes
...     ...   CPU records, exactly cpu_count records
```

The file size must be exactly:

```text
header_size + ram_size + cpu_count * 112
```

## CPU Record Layout

All v1 CPU records are fixed-size Rux16 records. Unknown CPU kinds are rejected.

```text
offset  size  field
0x00    4     cpu_kind: 1 for Rux16
0x04    4     state: 1 running, 2 halted, 3 trapped
0x08    8     max_steps
0x10    4     pc
0x14    4     trap_vector
0x18    4     trap_cause
0x1C    4     trap_pc
0x20    4     trap_value
0x24    4     reserved: 0
0x28    64    registers r0..r15, 32-bit each
0x68    8     metrics_steps
```

`max_steps` must be non-zero when restoring a CPU context. The trapped state is
restored as a trapped CPU with preserved trap CSRs; the human-readable trap
message is not serialized in v1.

## Restore Semantics

Full restore recreates RAM, CPU contexts, and `boot_cpu_id` from the snapshot
against an explicitly provided `ComputerMachineProfile`. Restore must reject a
snapshot when its `ram_size` differs from the target profile memory size, when
the boot CPU id points outside the CPU table, or when a CPU record contains an
unsupported kind/state/reserved field.

RAM-only restore remains available as an explicitly named operation for tooling
that only wants RAM bytes. It does not recreate CPU contexts, boot CPU id,
device buffers, display contents, pending storage commands, or machine
lifecycle state.

## Validation

A decoder must reject:

- invalid magic;
- unsupported version;
- unsupported header size;
- non-zero flags;
- RAM size values that do not fit the host;
- file length that does not exactly match the declared payload length;
- unknown CPU kinds;
- unknown Rux16 CPU states;
- non-zero CPU record reserved fields;
- zero `max_steps` when restoring CPU contexts.

There is no fallback decoder for unknown snapshot formats.
