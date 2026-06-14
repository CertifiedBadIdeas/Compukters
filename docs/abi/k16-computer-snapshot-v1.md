# K16 Computer Snapshot v1

## Status

Status: experimental.

`K16SNAP` is the host-side snapshot container for `ComputerMachine` runtime
state. It is not a guest-visible disk format and it is not stored on
`storage0`. Its purpose is to support future persistence of a running computer
across host unload/load boundaries.

The current v1 slice records a versioned header, full RAM bytes, fixed-size
K16 CPU continuation records, including trap and interrupt CSR state, and
explicit device records for `control`, `debug`, serial input, the `storage0`
controller, `timer0` game ticks, pending `keyboard0` events, and optional
`mmu0` translated address-space state.

## File Layout

All integers are little-endian.

```text
offset  size  field
0x00    8     magic: "K16SNAP\0"
0x08    2     version: 1
0x0A    2     header_size: 40
0x0C    4     flags: 0
0x10    8     ram_size
0x18    4     cpu_count
0x1C    4     boot_cpu_id, or 0xffffffff when absent
0x20    4     device_count
0x24    4     reserved: 0
0x28    ...   RAM bytes, exactly ram_size bytes
...     ...   CPU records, exactly cpu_count records
...     ...   Device records, exactly device_count records
```

The fixed payload prefix must contain:

```text
header_size + ram_size + cpu_count * 208
```

The final file size is that fixed prefix plus the decoded sizes of all device
records. Decoders must reject trailing bytes after the declared device records.

## CPU Record Layout

All v1 CPU records are fixed-size K16 records. Unknown CPU kinds are rejected.

```text
offset  size  field
0x00    4     cpu_kind: 1 for K16
0x04    4     state: 1 running, 2 halted, 3 trapped
0x08    8     max_steps
0x10    4     pc
0x14    4     trap_vector
0x18    4     trap_cause
0x1C    4     trap_pc
0x20    4     trap_value
0x24    4     interrupt_enable: 0 or 1
0x28    4     interrupt_mask
0x2C    4     interrupt_pending
0x30    4     timer0_interrupt_value
0x34    4     trap_stack_pointer
0x38    64    registers r0..r15, 32-bit each
0x78    8     metrics_steps
0x80    4     trap_arg0
0x84    4     trap_arg1
0x88    4     trap_arg2
0x8C    64    saved trap registers r0..r15, 32-bit each
0xCC    4     trap_kernel_stack_pointer
```

`max_steps` must be non-zero when restoring a CPU context. The trapped state is
restored as a trapped CPU with preserved trap CSRs; the human-readable trap
message is not serialized in v1. Pending interrupt state is restored with the
CPU record; `timer0_interrupt_value` is the cause-specific value used when a
pending timer0 interrupt is delivered after restore. `trap_arg0..trap_arg2`
preserve captured syscall arguments while the CPU is inside a synchronous
syscall trap; they are `0` for non-syscall traps and interrupts. Saved trap
registers preserve the interrupted register frame so `iret` after restore can
resume guest code with the same user registers, except for the `r0` return value
written by the trap handler. `trap_kernel_stack_pointer` is the physical kernel
stack pointer captured when translated user execution was activated; trap entry
from translated user execution switches the live stack pointer to this value.

## Device Record Layout

Device records have a common variable-length header:

```text
offset  size  field
0x00    4     device_kind
0x04    4     payload_size
0x08    ...   payload bytes
```

Supported device kinds:

```text
kind  payload
1     control: status i32, panic_code i32, exit_code i32
2     debug: raw debug output bytes
4     serial input: pending input bytes in read order
5     storage0 controller: status i32, error i32, lba_low u32,
      lba_high u32, block_count u32, buffer_addr u32, bytes_done u32,
      sequence u64
6     timer0: game_ticks u64
7     keyboard0: sequence u64, dropped_count u32, event_count u32,
      followed by event_count records of event_kind u32, code u32,
      modifiers u32, flags u32
9     mmu0: next_address_space_id u32, address_space_count u32,
      cpu_mode_count u32, reserved u32, followed by address-space records and
      CPU mode records
```

Unknown device kinds are rejected. `control` payloads must be exactly 12 bytes.
The transient `control.yield` request bit is not serialized.
`debug` payloads may be empty. `storage0` controller payloads must be exactly 36 bytes.
`timer0` payloads must be exactly 8 bytes. `keyboard0` payloads must contain
16 bytes of metadata followed by exactly `event_count * 16` event bytes.
`mmu0` payloads are present only when translated address spaces exist or a CPU
mode differs from the default physical/kernel state.

`storage0` media contents are not stored in `K16SNAP`; they remain part of the
configured storage media. `STORAGE0_MEDIA_STATUS` is derived from the restored
profile/media rather than serialized as controller state.

`timer0.game_ticks` is serialized so guest simulation time continues after
restore. `timer0.monotonic_nanos` is not serialized; a restored machine gets a
fresh host monotonic origin.

`keyboard0` pending events are serialized in read order. Restore recreates the
queue exactly, including `sequence` and `dropped_count`.

`mmu0` address-space records preserve host-managed translated mappings. Each
address-space record is:

```text
offset  size  field
0x00    4     address_space_id
0x04    4     mapping_count
0x08    ...   mapping records
```

Each mapping record is 16 bytes:

```text
offset  size  field
0x00    4     virtual_start
0x04    4     physical_start
0x08    4     page_count
0x0C    4     flags: bit 0 user_accessible, bit 1 writable, bit 2 executable
```

Each CPU mode record is 28 bytes:

```text
offset  size  field
0x00    4     cpu_index
0x04    8     current address mode: u32 kind, u32 address_space_id
0x0C    4     current privilege mode
0x10    8     saved trap-return address mode: u32 kind, u32 address_space_id
0x18    4     saved trap-return privilege mode
```

Address mode kind `0` is physical and uses address-space id `0`. Address mode
kind `1` is translated and uses the supplied address-space id. Privilege mode
`0` is kernel and `1` is user.

## Restore Semantics

Full restore recreates RAM, CPU contexts, `boot_cpu_id`, `control` state,
`debug` output, pending serial input bytes, `storage0` controller registers,
`timer0.game_ticks`, pending `keyboard0` events, `mmu0` address-space maps, and
non-default CPU address/privilege modes from the snapshot against an explicitly
provided `ComputerMachineProfile`. Restore must reject a snapshot when its
`ram_size` differs from the target profile memory size, when the boot CPU id
points outside the CPU table, when a CPU record contains an unsupported
kind/state/reserved field, when an `mmu0` mapping is invalid for the target
profile RAM size, or when the target profile does not expose a device recorded
by the snapshot.

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
- unknown K16 CPU states;
- non-zero CPU record reserved fields;
- zero `max_steps` when restoring CPU contexts;
- non-zero header reserved field;
- truncated device record headers or payloads;
- unknown device kinds;
- invalid fixed-size device payload lengths;
- invalid `storage0` controller payload length;
- invalid `timer0` payload length;
- invalid `keyboard0` payload length;
- invalid `keyboard0` event kind or flags;
- invalid `mmu0` payload length, mapping flags, mapping ranges, duplicate
  address-space ids, or CPU mode indices outside the CPU table;
- trailing bytes after declared device records.

There is no fallback decoder for unknown snapshot formats.
