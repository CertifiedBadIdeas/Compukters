# Rux Machine Profile v2

## Status

Status: draft.

This document defines the next computer-class Rux machine environment. It is a machine profile, not an image ABI. `RUXI` low image ABI v1 remains byte-compatible with this profile as long as the image decoder, instruction encoding, and instruction semantics are unchanged.

Programs must still be compiled or linked for the target machine profile. A `RUXI` v1 image that assumes machine profile v1 addresses is not automatically semantically compatible with machine profile v2.

## Goals

- Keep the CPU and image ABI independent from Minecraft-specific hardware.
- Make all guest-visible hardware optional.
- Remove mandatory guest-visible control MMIO.
- Add a boot info contract that tells software how RAM and guest-visible hardware are laid out.
- Support configurable page size for boot layout and MMIO allocation.
- Allow future laptop, desktop, headless, monitor, modem, and storage configurations without changing the image ABI.

## Non-Goals

- Do not define a full USB, PCI, ACPI, or firmware protocol.
- Do not require a display, serial port, storage endpoint, modem, timer, or power endpoint.
- Do not add virtual memory or MMU semantics.
- Do not change `RUXI` low image ABI v1.
- Do not require hotplug in the first implementation.

## Required Machine Components

The minimum machine has:

- CPU;
- byte-addressed linear RAM;
- a boot image loaded into RAM;
- boot info written by the host before execution starts.

No guest-visible MMIO endpoint is required. Lifecycle controls such as start, force stop, and reset are host/runtime actions unless the machine profile includes an optional power-management endpoint.

## Address Spaces

The machine has one 32-bit guest physical address space.

RAM occupies:

```text
0x0000_0000 .. ram_size - 1
```

MMIO endpoints occupy implementation-assigned ranges outside RAM. Computer-class profiles should allocate MMIO from:

```text
0x1000_0000 .. 0xFFFF_FFFF
```

Loads and stores dispatch as follows:

```text
if address is in RAM:
    access RAM
else if address is in an active MMIO range:
    access that endpoint
else:
    memory fault
```

Multi-byte RAM and MMIO accesses are little-endian and do not require alignment unless a specific endpoint says otherwise.

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
0x1C    4     hardware_table_version
```

`magic` is the ASCII bytes `RXBI` interpreted as little-endian `u32`.

`version` is `2` for this profile.

`hardware_table_addr` is a RAM address. For small hardware tables the host should place the table inside the boot page after `BootInfo`.

`hardware_table_version` starts at `1`. If a runtime supports hotplug, the host increments it whenever the hardware table changes.

## Hardware Table

The hardware table is an array of fixed-size entries. All fields are little-endian `u32`.

The table describes guest-visible hardware roots. An entry can represent a built-in endpoint, a physical port with an attached endpoint, an empty physical port, or a controller. It is not required to list every host-side implementation detail.

```text
offset  size  name
0x00    4     id
0x04    4     entry_kind
0x08    4     physical_kind
0x0C    4     flags
0x10    4     endpoint_kind
0x14    4     instance
0x18    4     mmio_base
0x1C    4     mmio_size
```

Entry size is 32 bytes.

`id` is stable for the current boot. If a hardware entry is removed during hotplug, the host must not reuse the same `id` until reboot.

`entry_kind` identifies what kind of hardware root this entry represents.

`physical_kind` identifies the physical connector or integration style.

`flags` describe hardware attributes such as built-in, removable, read-only, or removed.

`endpoint_kind` identifies the currently available guest-facing endpoint. Empty ports use `EMPTY`.

`instance` is the per-endpoint-kind instance number assigned by the host for this boot, for example `VIDEO` instance `0` and `VIDEO` instance `1`. Empty ports should use `0`.

`mmio_base` and `mmio_size` describe the endpoint register range. Both must be aligned to `page_size`. Entries that do not expose MMIO use `0` for both fields.

## Hardware Entry Rules

Valid profile v2 entries follow these rules:

- `INTERNAL_DEVICE` entries should use `physical_kind = INTERNAL` or `VIRTUAL` and a non-`EMPTY` `endpoint_kind`.
- `PORT` entries describe a connector. They may use `endpoint_kind = EMPTY`.
- Empty `PORT` entries must use `mmio_base = 0`, `mmio_size = 0`, and `instance = 0`.
- `CONTROLLER` entries should use `endpoint_kind = BUS` unless a later profile defines a more specific controller endpoint.
- Unknown `entry_kind` or `endpoint_kind` entries must be ignored by software that does not understand them.
- Known entries with invalid MMIO ranges must not be used by guest software.

## Entry Kinds

The core profile reserves these entry kind IDs:

| Kind | Name | Semantics |
| ---: | --- | --- |
| `1` | `INTERNAL_DEVICE` | Built-in guest-visible endpoint without a user-facing physical port. |
| `2` | `PORT` | Physical or logical connector that may be empty or have an attached endpoint. |
| `3` | `CONTROLLER` | Guest-visible controller that may enumerate child endpoints through its own protocol. |

Unknown entry kinds must be ignored by software that does not understand them.

## Physical Kinds

The core profile reserves these physical kind IDs:

| Kind | Name | Semantics |
| ---: | --- | --- |
| `0` | `INTERNAL` | Integrated into the machine, not user-removable as a port. |
| `1` | `USB_A` | USB-A-like external port. |
| `2` | `USB_C` | USB-C-like external port. |
| `3` | `SERIAL_PORT` | Serial connector. |
| `4` | `DISPLAY_PORT` | External display connector. |
| `5` | `STORAGE_BAY` | Storage slot or bay. |
| `6` | `NETWORK_PORT` | Wired network connector. |
| `7` | `EXPANSION_SLOT` | Generic expansion slot. |
| `8` | `WIRELESS` | Wireless or radio attachment point. |
| `9` | `VIRTUAL` | Host-provided virtual attachment with no physical connector. |

Unknown physical kinds may still be used if `endpoint_kind` is known.

## Endpoint Kinds

The core profile reserves these endpoint kind IDs:

| Kind | Name | Required | Semantics |
| ---: | --- | --- | --- |
| `0` | `EMPTY` | no | Empty port or entry without an attached endpoint. |
| `1` | `SERIAL` | no | Byte stream console or debug serial endpoint. |
| `2` | `VIDEO` | no | Display controller or virtual video card. |
| `3` | `INPUT` | no | Keyboard, mouse, touchpad, or other input source. |
| `4` | `STORAGE` | no | Block or byte-addressed storage endpoint. |
| `5` | `MODEM` | no | Packet/network communication endpoint. |
| `6` | `TIMER` | no | Time source, tick source, or alarm timer. |
| `7` | `POWER` | no | Guest-requested graceful shutdown/reboot interface. |
| `8` | `RNG` | no | Host-provided random source. |
| `9` | `BUS` | no | Controller endpoint that exposes child endpoint discovery through its own protocol. |

Unknown endpoint kinds must be ignored by software that does not understand them.

## Hardware Flags

The core profile reserves these flag bits:

| Bit | Name | Meaning |
| ---: | --- | --- |
| `0` | `BUILT_IN` | Hardware is integrated into the machine chassis/profile. |
| `1` | `REMOVABLE` | Hardware or attached endpoint may be removed without destroying the machine. |
| `2` | `READ_ONLY` | Endpoint cannot be modified by the guest. |
| `3` | `REMOVED` | Hardware table entry is stale; MMIO range must fault or ignore accesses. |

Additional bits are profile- or endpoint-specific.

## Ports And Connectors

Profile v2 may expose empty physical slots or ports to the guest through `PORT` entries with `endpoint_kind = EMPTY`.

The Minecraft host may also model some laptop USB slots, desktop expansion slots, monitor ports, or storage bays internally and hide them from the guest. A port should be listed only when guest software is expected to reason about that connector.

Complex buses should be represented by a `CONTROLLER` entry with an endpoint-specific protocol rather than forcing every child endpoint into the top-level hardware table.

## Optional Hardware

Software must treat every hardware entry as optional. A valid machine may expose zero hardware entries.

Examples:

Headless compute machine:

```text
hardware_count = 0
```

Notebook:

```text
INTERNAL_DEVICE  INTERNAL  VIDEO0  built-in
INTERNAL_DEVICE  INTERNAL  INPUT0  built-in
INTERNAL_DEVICE  INTERNAL  POWER0  built-in
```

Notebook with removable storage:

```text
INTERNAL_DEVICE  INTERNAL     VIDEO0    built-in
INTERNAL_DEVICE  INTERNAL     INPUT0    built-in
INTERNAL_DEVICE  INTERNAL     POWER0    built-in
PORT             USB_A        STORAGE0  removable
PORT             USB_A        EMPTY
```

Desktop with external monitor and modem:

```text
PORT  DISPLAY_PORT  VIDEO0  removable
PORT  USB_A         MODEM0  removable
```

## Video Endpoint Direction

A `VIDEO` endpoint should start as a framebuffer controller:

- framebuffer bytes live in RAM, not MMIO;
- MMIO registers hold width, height, pixel format, framebuffer address, stride, dirty rectangle, and present command;
- host reads RAM on present and sends display frame updates to viewers.

This keeps the VM responsible for drawing and keeps Minecraft screens as viewers of an endpoint output.

Future `VIDEO` revisions may add a command buffer or 2D acceleration without changing the image ABI.

## Power And Reboot

Host-level machine controls are outside the guest-visible hardware table:

- turn on;
- force power off;
- reset;
- destroy.

Guest-requested graceful shutdown or reboot requires an optional `POWER` endpoint. If no `POWER` endpoint is present, guest software cannot request shutdown through the machine profile.

The CPU `halt` signal is not poweroff. It only reports a terminal execution state to the host.

## Hotplug

Hotplug is optional for profile v2 implementations.

If hotplug is supported:

- hardware table changes increment `hardware_table_version`;
- removed hardware entries remain listed with `REMOVED` until reboot or table compaction rules are explicitly defined by a later profile;
- `id` values are not reused until reboot;
- MMIO ranges for removed entries must not be reassigned until reboot;
- guest software may poll `hardware_table_version` if no interrupt/event endpoint is present.

If hotplug is not supported, hardware table contents are fixed for the entire boot.

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
- hardware discovery comes from boot info and the hardware table;
- guest-visible control MMIO is no longer required;
- hardware endpoints are optional and dynamically described.

Toolchains should describe their target as:

```text
RUXI image ABI v1 + Rux machine profile v2
```

instead of only `RUXI v1`.
