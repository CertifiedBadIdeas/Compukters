# Rux Machine Profile v2

## Status

Status: draft.

This document defines the next computer-class Rux machine environment. It is a machine profile, not an image ABI. `RUXI` low image ABI v1 remains byte-compatible with this profile as long as the image decoder, instruction encoding, and instruction semantics are unchanged.

Programs must still be compiled or linked for the target machine profile. A `RUXI` v1 image that assumes machine profile v1 addresses is not automatically semantically compatible with machine profile v2.

## Goals

- Keep the CPU and image ABI independent from Minecraft-specific hardware semantics.
- Make all guest-visible MMIO hardware optional.
- Remove mandatory guest-visible control MMIO.
- Add a boot info contract that tells software how RAM and guest-visible MMIO ranges are laid out.
- Support configurable page size for boot layout and MMIO allocation.
- Allow future laptop, desktop, headless, monitor, modem, and storage configurations without changing the image ABI.

## Non-Goals

- Do not define a full USB, PCI, ACPI, or firmware protocol.
- Do not define generic device classes, physical connector kinds, flags, descriptors, or a common MMIO device header.
- Do not require a display, serial port, storage interface, modem, timer, or power-management interface.
- Do not add virtual memory or MMU semantics.
- Do not change `RUXI` low image ABI v1.
- Do not support hotplug in this profile.

## Required Machine Components

The minimum machine has:

- CPU;
- byte-addressed linear RAM;
- a boot image loaded into RAM;
- boot info written by the host before execution starts.

No guest-visible MMIO range is required. Lifecycle controls such as start, force stop, and reset are host/runtime actions unless a target machine profile defines a guest-visible power-management range.

## Address Spaces

The machine has one 32-bit guest physical address space.

RAM occupies:

```text
0x0000_0000 .. ram_size - 1
```

MMIO ranges are assigned by the host before boot and occupy implementation-assigned ranges outside RAM. Computer-class profiles should allocate MMIO from:

```text
0x1000_0000 .. 0xFFFF_FFFF
```

Loads and stores dispatch as follows:

```text
if address is in RAM:
    access RAM
else if address is in an active MMIO range:
    access that MMIO handler
else:
    memory fault
```

Multi-byte RAM and MMIO accesses are little-endian and do not require alignment unless a target-specific MMIO handler says otherwise.

## Configurable Page Size

Each machine has a `page_size` value in boot info.

`page_size` is not an MMU page. It is the machine allocation and alignment granularity used for:

- the reserved boot page;
- program load base;
- MMIO range alignment;
- framebuffer and command-buffer alignment recommendations;
- future memory-protection work.

Rules:

- `page_size` must be a power of two.
- `page_size` must be at least 256 bytes.
- `page_size` must be at most 65536 bytes.
- `ram_size` must be a multiple of `page_size`.

## Boot Memory Layout

The first RAM page is reserved for host-provided boot data:

```text
0x0000_0000                    page_size
| boot page                    |
                               ram_size
                               | guest program RAM |
```

For profile v2:

```text
BOOT_INFO_ADDR = 0x0000_0000
program_base = page_size
```

The host loads image memory sections starting at `program_base` unless a future profile explicitly defines a different loader contract.

The guest must not write the boot page unless it intentionally gives up access to boot data.

## BootInfo Layout

All fields are little-endian `u32`.

```text
offset  size  name
0x00    4     magic
0x04    4     version
0x08    4     ram_size
0x0C    4     page_size
0x10    4     program_base
0x14    4     hardware_table_addr
0x18    4     hardware_count
```

`magic` is the ASCII bytes `RXBI` interpreted as little-endian `u32`.

`version` is `2` for this profile.

`hardware_table_addr` is a RAM address when `hardware_count > 0`. For small hardware tables the host should place the table inside the boot page after `BootInfo`.

If `hardware_count = 0`, `hardware_table_addr` must be `0`.

BootInfo size is 28 bytes.

## Hardware Table

The hardware table is an array of fixed-size entries. All fields are little-endian `u32`.

The table describes guest-visible MMIO ranges. It does not describe device classes, physical connector kinds, capabilities, flags, or hotplug state.

The semantic meaning of each entry is defined by the target machine profile. For example, a notebook target profile may define hardware entry `1` as its display controller and entry `2` as its input controller, but those meanings are outside this core profile.

```text
offset  size  name
0x00    4     id
0x04    4     mmio_base
0x08    4     mmio_size
```

Entry size is 12 bytes.

`id` is stable for the current boot and must be unique within the hardware table.

`mmio_base` and `mmio_size` describe the MMIO range. Both must be non-zero and aligned to `page_size`.

Hardware table entries are static for the whole boot. This profile has no hotplug; physical attachment changes become guest-visible only after a reboot that rebuilds BootInfo and the hardware table.

## Hardware Entry Rules

Valid profile v2 entries follow these rules:

- If `hardware_count > 0`, the full hardware table byte range must fit in RAM.
- If `hardware_count > 0`, the hardware table must not overlap BootInfo.
- `id` values must be unique for the boot.
- `mmio_base` and `mmio_size` must be non-zero.
- `mmio_base` and `mmio_size` must be aligned to `page_size`.
- MMIO ranges must not overlap RAM.
- MMIO ranges must not overlap each other.
- The host assigns all MMIO ranges before boot.
- The VM execution core must not infer or allocate MMIO ranges.

## Target Machine Profiles

The core profile intentionally does not define what hardware entry `id` values mean.

A concrete target machine profile defines those meanings. For example:

```text
Rux notebook profile v1:
  hardware id 1 = display controller
  hardware id 2 = input controller
  hardware id 3 = power controller
  hardware id 4 = port controller

Rux headless profile v1:
  hardware id 1 = serial console
```

The register layout and behavior of each MMIO range are also target-profile-defined.

Programs are expected to be compiled or linked for a target machine profile, not for profile v2 alone.

## Power And Reboot

Host-level machine controls are outside the guest-visible hardware table:

- turn on;
- force power off;
- reset;
- destroy.

Guest-requested graceful shutdown or reboot requires a target-defined MMIO range that provides power-management semantics. If no such range is defined by the target profile, guest software cannot request shutdown through the machine profile.

The CPU `halt` signal is not poweroff. It only reports a terminal execution state to the host.

## Compatibility With RUXI Image ABI v1

This profile does not change `RUXI` low image ABI v1:

- image magic and version are unchanged;
- section encoding is unchanged;
- instruction tags and operand encodings are unchanged;
- instruction semantics are unchanged;
- decode and validation rules are unchanged.

This profile does change machine semantics compared with profile v1:

- the first RAM page is reserved for boot data;
- image memory sections are loaded at `program_base`;
- guest-visible MMIO range discovery comes from boot info and the hardware table;
- guest-visible control MMIO is no longer required;
- hardware semantics are target-profile-defined.

Toolchains should describe their target as:

```text
RUXI image ABI v1 + Rux machine profile v2
```

instead of only `RUXI v1`.
