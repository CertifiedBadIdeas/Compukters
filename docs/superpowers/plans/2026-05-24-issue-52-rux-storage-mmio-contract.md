# Rux Storage MMIO Contract Implementation Plan

> Issue: [#52](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/52)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first Rux computer storage MMIO port contract with optional media, RAM-buffer block I/O, documentation, and native VM tests.

**Architecture:** Storage is exposed as a stable computer-profile hardware port, not as a guaranteed attached disk. The port uses MMIO registers for command/status and uses guest RAM as the data buffer for block reads and writes. The implementation adds a narrow command-time memory access hook to `MachineBus` so normal MMIO devices remain simple while storage can validate and copy guest RAM safely.

**Tech Stack:** Rust 2021 in `native/rux-vm`, existing `MachineBus`/`MmioDevice`, existing computer profile v2 boot info and hardware table, Markdown ABI docs.

---

### Task 1: Add ABI Constants And Profile Documentation

**Files:**
- Modify: `native/rux-vm/src/computer_abi.rs`
- Modify: `docs/abi/rux-computer-profile-v1.md`
- Test: `native/rux-vm/src/computer/machine.rs`

- [ ] **Step 1: Write the failing profile test**

Add this assertion to `computer_machine_writes_profile_v2_boot_info` and `computer_machine_can_be_created_from_explicit_computer_v1_profile` in `native/rux-vm/src/computer/machine.rs`:

```rust
assert_eq!(read_u32(machine.memory(), 0x18), 5);
assert_hardware_entry(
    machine.memory(),
    76,
    computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
    computer_abi::STORAGE0_BASE,
    computer_abi::PROFILE_V2_PAGE_SIZE,
);
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd native/rux-vm && cargo test computer_machine_writes_profile_v2_boot_info computer_machine_can_be_created_from_explicit_computer_v1_profile
```

Expected: compile failure because `COMPUTER_HARDWARE_ID_STORAGE0` and `STORAGE0_BASE` do not exist, or assertion failure because the hardware count is still `4`.

- [ ] **Step 3: Add storage constants**

Add to `native/rux-vm/src/computer_abi.rs`:

```rust
pub const COMPUTER_HARDWARE_ID_STORAGE0: u32 = 5;

pub const STORAGE0_BASE: u32 = 0x1000_0400;
pub const STORAGE0_VERSION: u32 = STORAGE0_BASE;
pub const STORAGE0_STATUS: u32 = STORAGE0_BASE + 4;
pub const STORAGE0_ERROR: u32 = STORAGE0_BASE + 8;
pub const STORAGE0_COMMAND: u32 = STORAGE0_BASE + 12;
pub const STORAGE0_BLOCK_SIZE: u32 = STORAGE0_BASE + 16;
pub const STORAGE0_CAPACITY_BLOCKS_LOW: u32 = STORAGE0_BASE + 20;
pub const STORAGE0_CAPACITY_BLOCKS_HIGH: u32 = STORAGE0_BASE + 24;
pub const STORAGE0_LBA_LOW: u32 = STORAGE0_BASE + 28;
pub const STORAGE0_LBA_HIGH: u32 = STORAGE0_BASE + 32;
pub const STORAGE0_BLOCK_COUNT: u32 = STORAGE0_BASE + 36;
pub const STORAGE0_BUFFER_ADDR: u32 = STORAGE0_BASE + 40;
pub const STORAGE0_BYTES_DONE: u32 = STORAGE0_BASE + 44;
pub const STORAGE0_SEQUENCE_LOW: u32 = STORAGE0_BASE + 48;
pub const STORAGE0_SEQUENCE_HIGH: u32 = STORAGE0_BASE + 52;
pub const STORAGE0_MEDIA_STATUS: u32 = STORAGE0_BASE + 56;
pub const STORAGE0_SIZE: u32 = PROFILE_V2_PAGE_SIZE;

pub const STORAGE_VERSION: i32 = 1;

pub const STORAGE_STATUS_READY: i32 = 0;
pub const STORAGE_STATUS_BUSY: i32 = 1;
pub const STORAGE_STATUS_DONE: i32 = 2;
pub const STORAGE_STATUS_ERROR: i32 = 3;

pub const STORAGE_ERROR_NONE: i32 = 0;
pub const STORAGE_ERROR_INVALID_COMMAND: i32 = 1;
pub const STORAGE_ERROR_MEDIA_ABSENT: i32 = 2;
pub const STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS: i32 = 3;
pub const STORAGE_ERROR_LBA_OUT_OF_BOUNDS: i32 = 4;
pub const STORAGE_ERROR_BYTE_COUNT_OVERFLOW: i32 = 5;
pub const STORAGE_ERROR_WRITE_PROTECTED: i32 = 6;
pub const STORAGE_ERROR_IO_ERROR: i32 = 7;

pub const STORAGE_COMMAND_NOP: i32 = 0;
pub const STORAGE_COMMAND_READ_BLOCKS: i32 = 1;
pub const STORAGE_COMMAND_WRITE_BLOCKS: i32 = 2;
pub const STORAGE_COMMAND_FLUSH: i32 = 3;

pub const STORAGE_MEDIA_ABSENT: i32 = 0;
pub const STORAGE_MEDIA_PRESENT: i32 = 1;
pub const STORAGE_MEDIA_READ_ONLY: i32 = 2;
pub const STORAGE_MEDIA_ERROR: i32 = 3;
```

- [ ] **Step 4: Update computer profile docs**

In `docs/abi/rux-computer-profile-v1.md`, add `storage0` to the hardware table and add a Storage0 MMIO section matching the design doc register layout and constants.

- [ ] **Step 5: Run the focused tests**

Run:

```bash
cd native/rux-vm && cargo test computer_machine_writes_profile_v2_boot_info computer_machine_can_be_created_from_explicit_computer_v1_profile
```

Expected: tests still fail until Task 2 wires the profile entry.

---

### Task 2: Add Storage Port Configuration To ComputerMachineProfile

**Files:**
- Modify: `native/rux-vm/src/computer/profile.rs`
- Modify: `native/rux-vm/src/computer/machine.rs`
- Test: `native/rux-vm/src/computer/machine.rs`

- [ ] **Step 1: Add a failing storage profile test**

Add this test to `native/rux-vm/src/computer/machine.rs`:

```rust
#[test]
fn computer_profile_can_expose_storage0_without_attached_media() {
    let profile = ComputerMachineProfile::new(1024).with_hardware(
        ComputerHardwareConfig::storage_port(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
        ),
    );

    let machine = ComputerMachine::from_profile(profile).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 1);
    assert_hardware_entry(
        machine.memory(),
        28,
        computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
        computer_abi::STORAGE0_BASE,
        computer_abi::STORAGE0_SIZE,
    );
    assert!(machine.memory_map().region("storage0").is_some());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd native/rux-vm && cargo test computer_profile_can_expose_storage0_without_attached_media
```

Expected: compile failure because `ComputerHardwareConfig::storage_port` does not exist.

- [ ] **Step 3: Add storage profile variant**

In `native/rux-vm/src/computer/profile.rs`:

```rust
pub fn storage_port(id: u32, mmio_base: u32) -> Self {
    Self {
        id,
        mmio_base,
        device: ComputerHardwareDevice::StoragePort,
    }
}
```

Extend `ComputerHardwareDevice`:

```rust
StoragePort,
```

Return `computer_abi::STORAGE0_SIZE` from `mmio_size()` for `StoragePort`.

- [ ] **Step 4: Wire storage into the default computer profile**

In `ComputerMachineProfile::computer_v1`, append:

```rust
.with_hardware(ComputerHardwareConfig::storage_port(
    computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
    computer_abi::STORAGE0_BASE,
))
```

- [ ] **Step 5: Track storage memory map in ComputerMachine**

In `native/rux-vm/src/computer/machine.rs`, add:

```rust
storage0_device_id: Option<MmioDeviceId>,
```

Map `ComputerHardwareDevice::StoragePort` and push a memory map region named `"storage0"`.

- [ ] **Step 6: Run focused profile tests**

Run:

```bash
cd native/rux-vm && cargo test computer_profile_can_expose_storage0_without_attached_media computer_machine_writes_profile_v2_boot_info computer_machine_can_be_created_from_explicit_computer_v1_profile
```

Expected: tests pass after Task 3 adds the storage port device type.

---

### Task 3: Add StoragePortDevice With Absent Media Semantics

**Files:**
- Modify: `native/rux-vm/src/computer/devices.rs`
- Modify: `native/rux-vm/src/computer/profile.rs`
- Modify: `native/rux-vm/src/computer/machine.rs`
- Test: `native/rux-vm/src/computer/machine.rs`

- [ ] **Step 1: Add failing absent-media tests**

Add this test to `native/rux-vm/src/computer/machine.rs`:

```rust
#[test]
fn storage0_absent_media_reports_zero_capacity_and_media_absent_errors() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_VERSION).unwrap(),
        computer_abi::STORAGE_VERSION,
    );
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_MEDIA_STATUS).unwrap(),
        computer_abi::STORAGE_MEDIA_ABSENT,
    );
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_CAPACITY_BLOCKS_LOW).unwrap(),
        0,
    );
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
        computer_abi::STORAGE_STATUS_ERROR,
    );
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_ERROR).unwrap(),
        computer_abi::STORAGE_ERROR_MEDIA_ABSENT,
    );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd native/rux-vm && cargo test storage0_absent_media_reports_zero_capacity_and_media_absent_errors
```

Expected: compile failure because storage constants/device are not wired, or runtime failure because the MMIO range is not mapped.

- [ ] **Step 3: Add test-only bus accessors if needed**

If tests cannot access storage MMIO through public methods, add these `pub(crate)` helpers to `ComputerMachine`:

```rust
pub(crate) fn bus_load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
    self.bus.load_i32(address)
}

pub(crate) fn bus_store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
    self.bus.store_i32(address, value)
}
```

- [ ] **Step 4: Implement absent-media StoragePortDevice**

Add `StoragePortDevice` to `native/rux-vm/src/computer/devices.rs` with these fields:

```rust
pub(crate) struct StoragePortDevice {
    status: i32,
    error: i32,
    lba: u64,
    block_count: u32,
    buffer_addr: u32,
    bytes_done: u32,
    sequence: u64,
}
```

For this task, no media is attached. Reads return:

- `version = STORAGE_VERSION`;
- `status`, `error`;
- `block_size = 512`;
- `capacity = 0`;
- `media_status = STORAGE_MEDIA_ABSENT`.

Commands:

- `NOP` succeeds;
- `READ_BLOCKS` and `WRITE_BLOCKS` fail with `MEDIA_ABSENT`;
- `FLUSH` succeeds.

- [ ] **Step 5: Run absent-media tests**

Run:

```bash
cd native/rux-vm && cargo test storage0_absent_media_reports_zero_capacity_and_media_absent_errors
```

Expected: PASS.

---

### Task 4: Add Command-Time RAM Buffer I/O For Attached Media

**Files:**
- Modify: `native/rux-vm/src/low_bus.rs`
- Modify: `native/rux-vm/src/computer/devices.rs`
- Modify: `native/rux-vm/src/computer/profile.rs`
- Test: `native/rux-vm/src/computer/machine.rs`

- [ ] **Step 1: Add failing read/write tests**

Add tests that configure a storage port with in-memory media bytes:

```rust
#[test]
fn storage0_read_blocks_copies_media_into_guest_ram() {
    let media = vec![0xA5; 512];
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            media,
            false,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();

    machine.bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512).unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
        computer_abi::STORAGE_STATUS_DONE,
    );
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_BYTES_DONE).unwrap(),
        512,
    );
    assert_eq!(machine.memory().bytes()[512], 0xA5);
    assert_eq!(machine.memory().bytes()[1023], 0xA5);
}

#[test]
fn storage0_write_blocks_copies_guest_ram_into_media() {
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            vec![0; 512],
            false,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine.memory_mut().store_u8(512, 0x5A).unwrap();
    machine.memory_mut().store_u8(1023, 0xC3).unwrap();

    machine.bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1).unwrap();
    machine.bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512).unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
        )
        .unwrap();

    let media = machine.storage0_media_bytes().unwrap();
    assert_eq!(media[0], 0x5A);
    assert_eq!(media[511], 0xC3);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd native/rux-vm && cargo test storage0_read_blocks_copies_media_into_guest_ram storage0_write_blocks_copies_guest_ram_into_media
```

Expected: compile failure because `storage_port_with_media`, RAM command handling, and `storage0_media_bytes` do not exist.

- [ ] **Step 3: Add command-time memory hook**

In `native/rux-vm/src/low_bus.rs`, extend `MmioDevice`:

```rust
fn store_i32_with_memory(
    &mut self,
    offset: u32,
    value: i32,
    _memory: &mut MachineMemory,
) -> Result<(), MemoryFault> {
    self.store_i32(offset, value)
}
```

In `MachineBus::store_i32`, call `store_i32_with_memory(offset, value, &mut self.memory)` for MMIO regions.

- [ ] **Step 4: Add attached media config**

Add a `StoragePortConfig` or equivalent internal enum so
`ComputerHardwareConfig::storage_port_with_media(id, base, media, read_only)`
can construct a storage port with attached media.

The media vector length must be a multiple of `512`. Invalid lengths should be
rejected during machine creation with a clear `MemoryFault`.

- [ ] **Step 5: Implement read/write command handling**

In `StoragePortDevice::store_i32_with_memory`, when writing `COMMAND`, validate
the command and copy bytes between media and `MachineMemory`.

Validation order:

1. invalid command;
2. absent/error media;
3. read-only media for writes;
4. byte count overflow;
5. LBA range;
6. guest RAM buffer range.

On success set `status = DONE`, `error = NONE`, `bytes_done = byte_count`, and
increment `sequence`.

On failure set `status = ERROR`, `error = code`, `bytes_done = 0`, and increment
`sequence`.

- [ ] **Step 6: Expose media bytes for tests**

Add `pub(crate) fn storage0_media_bytes(&self) -> Option<&[u8]>` to
`ComputerMachine` by downcasting the mapped storage device.

- [ ] **Step 7: Run read/write tests**

Run:

```bash
cd native/rux-vm && cargo test storage0_read_blocks_copies_media_into_guest_ram storage0_write_blocks_copies_guest_ram_into_media
```

Expected: PASS.

---

### Task 5: Add Storage Error Path Coverage

**Files:**
- Modify: `native/rux-vm/src/computer/machine.rs`
- Modify: `native/rux-vm/src/computer/devices.rs`

- [ ] **Step 1: Add failing error tests**

Add tests for:

```rust
storage0_read_blocks_rejects_out_of_bounds_lba
storage0_read_blocks_rejects_out_of_bounds_guest_buffer
storage0_write_blocks_rejects_read_only_media
storage0_invalid_command_sets_invalid_command_error
```

Each test should write registers, issue the command, and assert `STATUS_ERROR`,
the exact `STORAGE_ERROR_*` value, and `BYTES_DONE == 0`.

- [ ] **Step 2: Run tests to verify failure if coverage is missing**

Run:

```bash
cd native/rux-vm && cargo test storage0_
```

Expected: any missing behavior fails.

- [ ] **Step 3: Complete validation branches**

Complete `StoragePortDevice` validation until all tests pass. Use checked
arithmetic for:

```rust
block_count * block_size
lba + block_count
buffer_addr + byte_count
```

- [ ] **Step 4: Run all native VM tests**

Run:

```bash
cd native/rux-vm && cargo test
```

Expected: PASS.

---

### Task 6: Final Verification And Roadmap Update

**Files:**
- Verify: `docs/superpowers/specs/2026-05-24-issue-52-rux-storage-mmio-contract-design.md`
- Verify: `docs/abi/rux-computer-profile-v1.md`
- Verify: `native/rux-vm/src/computer_abi.rs`
- Verify: `native/rux-vm/src/computer/devices.rs`
- Verify: `native/rux-vm/src/computer/profile.rs`
- Verify: `native/rux-vm/src/computer/machine.rs`

- [ ] **Step 1: Run placeholder scans**

Run:

```bash
rg -n "TBD|TODO|FIXME|placeholder" docs/abi docs/superpowers/specs/2026-05-24-issue-52-rux-storage-mmio-contract-design.md native/rux-vm/src
```

Expected: no new placeholders from this work.

- [ ] **Step 2: Run full relevant tests**

Run:

```bash
cd native/rux-vm && cargo test
cd native/rux-compiler && cargo test
```

Expected: PASS.

- [ ] **Step 3: Check git diff**

Run:

```bash
git diff --stat
git status --short
```

Expected: only storage contract files changed.

- [ ] **Step 4: Update roadmap**

If all acceptance criteria are implemented and tests pass, close #52 as
completed and set the project status to Done. If implementation is partial,
leave #52 in Now and list the remaining unchecked acceptance criteria.
