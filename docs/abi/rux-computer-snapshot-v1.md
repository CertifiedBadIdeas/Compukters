# Rux Computer Snapshot v1

## Status

Status: experimental.

`RUXSNAP` is the host-side snapshot container for `ComputerMachine` runtime
state. It is not a guest-visible disk format and it is not stored on
`storage0`. Its purpose is to support future persistence of a running computer
across host unload/load boundaries.

The first v1 slice records a versioned header and full RAM bytes. CPU and
device state fields are represented only as header summary data in this slice;
full continuation from a saved `pc` is a later snapshot slice.

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
```

The file size must be exactly `header_size + ram_size`.

## Restore Semantics

The current implementation supports RAM-only restore into an explicitly
provided `ComputerMachineProfile`. Restore must reject a snapshot when its
`ram_size` differs from the target profile memory size.

RAM-only restore deliberately does not recreate CPU contexts, boot CPU id,
device buffers, display contents, pending storage commands, or machine
lifecycle state. That keeps this slice honest: it can persist RAM bytes, but it
cannot yet resume a running machine.

## Validation

A decoder must reject:

- invalid magic;
- unsupported version;
- unsupported header size;
- non-zero flags;
- RAM size values that do not fit the host;
- file length that does not exactly match the declared RAM payload length.

There is no fallback decoder for unknown snapshot formats.
