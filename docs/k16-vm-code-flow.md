# K16 VM Code Flow

This document is a reader map for the current Rust K16 VM implementation. It
does not define guest ABI. Guest-visible contracts live under `docs/abi/`.

## First Files To Open

| File | Responsibility |
| --- | --- |
| `host/k16-vm/src/computer/handle.rs` | Public host-facing K16 computer handle used by JNI and tests. |
| `host/k16-vm/src/computer/machine.rs` | Owns a complete computer: RAM, MMIO bus, devices, CPU table, boot CPU, snapshots, and high-level control methods. |
| `host/k16-vm/src/low_bus.rs` | Routes memory accesses either to flat RAM or to mapped MMIO devices. |
| `host/k16-vm/src/low_machine.rs` | Flat little-endian guest RAM and the `MemoryBus` trait used by CPUs. |
| `host/k16-vm/src/k16.rs` | Kraft16 CPU state, instruction decoder, execution loop, traps, CSR handling, and call stack semantics. |
| `host/k16-vm/src/computer/devices.rs` | Host implementations of K16 MMIO devices: control, debug serial, serial input, gpu0, storage0, keyboard0, timer0, and BIOS flash. |
| `host/k16-vm/src/computer/profile.rs` | Declarative hardware profiles and hardware-table validation. |
| `host/k16-vm/src/computer/snapshot.rs` | Host-side `ComputerMachine` snapshot encoding and decoding. |

## Host-To-CPU Flow

```text
Kotlin / JNI
  -> K16ComputerHandle
     -> ComputerMachine
        -> boot CPU in ComputerCpuContext::K16
           -> K16Cpu::run_until_signal
              -> K16Cpu::step
                 -> K16Decoder::decode
                    -> MachineBus::load_u16(pc)
                       -> BIOS flash MMIO or RAM
                 -> execute DecodedInstruction
                    -> MachineBus load/store
                       -> RAM or MMIO device
```

The VM is advanced on demand. The host asks the native handle to run the boot
CPU until one of two signals appears:

- `Halt`: the guest halted, and `ComputerMachine` writes the halted status into
  the control device.
- `StepLimitExceeded`: the CPU consumed its per-call budget and should be called
  again later.

If a K16 exception is not handled by a guest trap vector, `ComputerMachine`
stores a stable panic code in the control device and returns the error to the
host.

## MachineBus Routing

`MachineBus` has two responsibilities:

1. preserve the guest-visible priority of MMIO over RAM;
2. make ordinary RAM access cheap.

For every access it checks whether the address is fully covered by a mapped
MMIO region. If so, the request is forwarded to that device. Otherwise the
request goes to `MachineMemory`.

This matters for instruction fetch. K16 instructions are little-endian 16-bit
words. The CPU fetches them through `MemoryBus::load_u16`, and `MachineBus`
overrides `load_u16` so ordinary RAM and BIOS/RAM fetches do not degrade into
two independent byte loads.

## Decode And Execute

`K16Decoder` turns one or more instruction words into a `DecodedInstruction`
plus `next_pc`.

`K16Cpu::step_with_decoder` then follows the same sequence every time:

1. reject stepping if the CPU is halted or already trapped;
2. decode at the current `pc`;
3. increment the step counter;
4. move `pc` to `next_pc`;
5. execute the decoded instruction;
6. override `pc` only for branch, jump, call, return, or exception paths.

The decoder owns binary instruction format details. The CPU owns effects on
registers, memory, CSR state, traps, and signals.

## Trap Model

K16 has a small CSR-backed exception model:

- `trap_vector`: guest address to jump to when an exception is handled;
- `trap_cause`: numeric cause;
- `trap_pc`: guest PC where the fault happened;
- `trap_value`: fault-specific value such as an address or CSR id.

If `trap_vector == 0`, exceptions are unhandled. The CPU enters `Trapped`, and
the host machine reports a panic through the control device. If `trap_vector` is
non-zero, the CPU records trap CSRs and jumps to the guest handler instead.

## ComputerMachine Responsibilities

`ComputerMachine` is the boundary between CPU-level execution and a full
computer profile.

It owns:

- guest RAM;
- the `MachineBus`;
- mapped MMIO devices;
- the CPU table;
- the boot CPU id;
- snapshot creation and restore;
- host-visible accessors for control, debug output, serial input, keyboard
  input, and gpu0 display frames.

It does not decode instructions itself. It delegates execution to `K16Cpu` and
reacts to the returned signal or error.

## MMIO Device Flow

Guest code talks to devices by loading or storing addresses from the hardware
table.

```text
guest instruction
  -> K16Cpu load/store execution
     -> MachineBus
        -> matching MmioRegion
           -> concrete device in computer/devices.rs
```

Device state is host-owned. For example:

- `DebugSerialDevice` collects bytes for host-side draining.
- `SerialInputDevice` consumes bytes pushed by the host.
- `GpuDevice` turns guest RAM blits into `DisplayFrameDelta` values.
- `StoragePortDevice` copies blocks between guest RAM and storage media.
- `BiosFlashDevice` exposes read-only firmware bytes at the high BIOS address.

## Safe Refactor Boundaries

Good small refactor slices:

- split decode helper functions inside `k16.rs`;
- extract CPU execution helpers for load/store/call/return fault handling;
- move K16 instruction types into a dedicated module;
- split `computer/devices.rs` by device family;
- split `computer/machine.rs` into profile construction, CPU lifecycle,
  snapshot handling, and host accessors.

Risky slices that need separate issue/spec work:

- changing MMIO priority or address coverage rules;
- changing snapshot layout;
- changing hardware-table contents;
- changing guest-visible instruction encoding or trap semantics;
- changing JNI handle ownership or lifecycle.
