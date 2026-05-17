# Rux Machine Profile v2

## Status

Status: draft.

This document defines the next computer-class Rux machine environment. It is a machine profile, not an image ABI. `RUXI` low image ABI v1 remains byte-compatible with this profile as long as the image decoder, instruction encoding, and instruction semantics are unchanged.

Programs must still be compiled or linked for the target machine profile. A `RUXI` v1 image that assumes machine profile v1 addresses is not automatically semantically compatible with machine profile v2.

## Goals

- Keep the CPU and image ABI independent from Minecraft-specific devices.
- Make all guest-visible devices optional.
- Remove mandatory guest-visible control MMIO.
- Add a boot info contract that tells software how RAM and devices are laid out.
- Support configurable page size for boot layout and MMIO allocation.
- Allow future laptop, desktop, headless, monitor, modem, and storage configurations without changing the image ABI.

## Non-Goals

- Do not define a full USB, PCI, ACPI, or firmware protocol.
- Do not require a display, serial port, storage device, modem, timer, or power device.
- Do not add virtual memory or MMU semantics.
- Do not change `RUXI` low image ABI v1.
- Do not require hotplug in the first implementation.

## Required Machine Components

The minimum machine has:

- CPU;
- byte-addressed linear RAM;
- a boot image loaded into RAM;
- boot info written by the host before execution starts.

No guest-visible MMIO device is required. Lifecycle controls such as start, force stop, and reset are host/runtime actions unless the machine profile includes an optional power-management device.

## Address Spaces

The machine has one 32-bit guest physical address space.

RAM occupies:

```text
0x0000_0000 .. ram_size - 1
```

MMIO devices occupy implementation-assigned ranges outside RAM. Computer-class profiles should allocate MMIO from:

```text
0x1000_0000 .. 0xFFFF_FFFF
```

Loads and stores dispatch as follows:

```text
if address is in RAM:
    access RAM
else if address is in an active MMIO range:
    access that device
else:
    memory fault
```

Multi-byte RAM and MMIO accesses are little-endian and do not require alignment unless a specific device says otherwise.

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
0x14    4     device_table_addr
0x18    4     device_count
0x1C    4     device_table_version
```

`magic` is the ASCII bytes `RXBI` interpreted as little-endian `u32`.

`version` is `2` for this profile.

`device_table_addr` is a RAM address. For small device tables the host should place the table inside the boot page after `BootInfo`.

`device_table_version` starts at `1`. If a runtime supports hotplug, the host increments it whenever the device table changes.

## Device Table

The device table is an array of fixed-size entries. All fields are little-endian `u32`.

```text
offset  size  name
0x00    4     id
0x04    4     type
0x08    4     instance
0x0C    4     flags
0x10    4     mmio_base
0x14    4     mmio_size
```

Entry size is 24 bytes.

`id` is stable for the current boot. If a device is removed during hotplug, the host must not reuse the same `id` until reboot.

`type` identifies the kind of device.

`instance` is the per-type instance number assigned by the host for this boot, for example `VIDEO` instance `0` and `VIDEO` instance `1`.

`flags` describe device attributes such as built-in, removable, read-only, or removed.

`mmio_base` and `mmio_size` describe the device register range. Both must be aligned to `page_size`. Devices that do not expose MMIO use `0` for both fields.

## Device Types

The core profile reserves these type IDs:

| Type | Name | Required | Semantics |
| ---: | --- | --- | --- |
| `1` | `SERIAL` | no | Byte stream console or debug serial endpoint. |
| `2` | `VIDEO` | no | Display controller or virtual video card. |
| `3` | `INPUT` | no | Keyboard, mouse, touchpad, or other input source. |
| `4` | `STORAGE` | no | Block or byte-addressed storage device. |
| `5` | `MODEM` | no | Packet/network communication device. |
| `6` | `TIMER` | no | Time source, tick source, or alarm timer. |
| `7` | `POWER` | no | Guest-requested graceful shutdown/reboot interface. |
| `8` | `RNG` | no | Host-provided random source. |

Unknown device types must be ignored by software that does not understand them.

## Device Flags

The core profile reserves these flag bits:

| Bit | Name | Meaning |
| ---: | --- | --- |
| `0` | `BUILT_IN` | Device is integrated into the machine chassis/profile. |
| `1` | `REMOVABLE` | Device may be removed without destroying the machine. |
| `2` | `READ_ONLY` | Device cannot be modified by the guest. |
| `3` | `REMOVED` | Device table entry is stale; MMIO range must fault or ignore accesses. |

Additional bits are profile- or device-specific.

## Slots And Connectors

Profile v2 does not expose empty physical slots or ports to the guest by default.

The Minecraft host may model laptop USB slots, desktop expansion slots, monitor ports, or storage bays internally. The guest sees only devices that are currently attached and listed in the device table.

If a future profile needs guest-visible connector discovery, it should add an optional `SLOT_CONTROLLER` or `BUS` device instead of making connector metadata mandatory.

## Optional Devices

Software must treat every device entry as optional. A valid machine may expose zero devices.

Examples:

Headless compute machine:

```text
device_count = 0
```

Notebook:

```text
VIDEO0  built-in
INPUT0  built-in
POWER0  built-in
```

Notebook with removable storage:

```text
VIDEO0    built-in
INPUT0    built-in
POWER0    built-in
STORAGE0  removable
```

Desktop with external monitor and modem:

```text
VIDEO0  removable
MODEM0  removable
```

## Video Device Direction

A `VIDEO` device should start as a framebuffer controller:

- framebuffer bytes live in RAM, not MMIO;
- MMIO registers hold width, height, pixel format, framebuffer address, stride, dirty rectangle, and present command;
- host reads RAM on present and sends display frame updates to viewers.

This keeps the VM responsible for drawing and keeps Minecraft screens as viewers of a device output.

Future `VIDEO` revisions may add a command buffer or 2D acceleration without changing the image ABI.

## Power And Reboot

Host-level machine controls are outside the guest-visible device table:

- turn on;
- force power off;
- reset;
- destroy.

Guest-requested graceful shutdown or reboot requires an optional `POWER` device. If no `POWER` device is present, guest software cannot request shutdown through the machine profile.

The CPU `halt` signal is not poweroff. It only reports a terminal execution state to the host.

## Hotplug

Hotplug is optional for profile v2 implementations.

If hotplug is supported:

- device table changes increment `device_table_version`;
- removed devices remain listed with `REMOVED` until reboot or table compaction rules are explicitly defined by a later profile;
- `id` values are not reused until reboot;
- MMIO ranges for removed devices must not be reassigned until reboot;
- guest software may poll `device_table_version` if no interrupt/event device is present.

If hotplug is not supported, device table contents are fixed for the entire boot.

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
- device discovery comes from boot info and the device table;
- guest-visible control MMIO is no longer required;
- devices are optional and dynamically described.

Toolchains should describe their target as:

```text
RUXI image ABI v1 + Rux machine profile v2
```

instead of only `RUXI v1`.
