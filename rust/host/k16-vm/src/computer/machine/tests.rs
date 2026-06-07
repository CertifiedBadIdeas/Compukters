use super::ComputerMachine;
use crate::computer::devices::{
    ComputerControlDevice, DebugSerialDevice, GpuDevice, KeyboardDevice, SerialInputDevice,
    TextDisplayDevice,
};
use crate::computer::profile::{ComputerHardwareConfig, ComputerMachineProfile};
use crate::computer_abi;
use crate::low_bus::MmioDevice;
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn computer_machine_owns_shared_physical_ram() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.memory_mut().store_i32(128, 42).unwrap();

    assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
}

#[test]
fn computer_serial_input_device_reports_ready_and_consumes_bytes() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    machine.push_serial_input(b"OK");

    assert_eq!(machine.serial_input_len(), 2);
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::SERIAL_INPUT_READY)
            .unwrap(),
        1
    );
    assert_eq!(
        machine
            .bus
            .load_u8(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        b'O'
    );
    assert_eq!(machine.serial_input_len(), 1);
    assert_eq!(
        machine
            .bus
            .load_u8(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        b'K'
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::SERIAL_INPUT_READY)
            .unwrap(),
        0
    );
    assert_eq!(
        machine
            .bus
            .load_u8(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        0
    );
}

#[test]
fn computer_keyboard0_device_reports_front_event_and_consumes_it() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.push_keyboard_key_down(257, true, computer_abi::KEYBOARD0_MOD_CONTROL);
    machine.push_keyboard_char(b'R');

    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_QUEUE_LEN)
            .unwrap(),
        2
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_STATUS)
            .unwrap(),
        computer_abi::KEYBOARD0_STATUS_READY
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_EVENT_KIND)
            .unwrap(),
        computer_abi::KEYBOARD0_EVENT_KEY_DOWN
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_CODE)
            .unwrap(),
        257
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_MODIFIERS)
            .unwrap(),
        computer_abi::KEYBOARD0_MOD_CONTROL
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_FLAGS)
            .unwrap(),
        computer_abi::KEYBOARD0_FLAG_REPEAT
    );

    machine
        .bus
        .store_i32(
            ComputerMachine::KEYBOARD0_COMMAND,
            computer_abi::KEYBOARD0_COMMAND_CONSUME,
        )
        .unwrap();

    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_EVENT_KIND)
            .unwrap(),
        computer_abi::KEYBOARD0_EVENT_CHAR
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_CODE)
            .unwrap(),
        i32::from(b'R')
    );
    assert_eq!(machine.keyboard0_len(), 1);
}

#[test]
fn computer_keyboard0_clear_empties_queue_and_advances_sequence() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.push_keyboard_char(b'A');
    let sequence_before_clear = read_keyboard0_sequence(&machine);

    machine
        .bus
        .store_i32(
            ComputerMachine::KEYBOARD0_COMMAND,
            computer_abi::KEYBOARD0_COMMAND_CLEAR,
        )
        .unwrap();

    assert_eq!(machine.keyboard0_len(), 0);
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_STATUS)
            .unwrap(),
        computer_abi::KEYBOARD0_STATUS_EMPTY
    );
    assert!(read_keyboard0_sequence(&machine) > sequence_before_clear);
}

#[test]
fn computer_keyboard0_drops_newest_event_on_overflow() {
    let mut keyboard = KeyboardDevice::with_capacity_for_tests(2);

    keyboard.push_char(b'A');
    keyboard.push_char(b'B');
    keyboard.push_char(b'C');

    assert_eq!(keyboard.len(), 2);
    assert_eq!(keyboard.dropped_count(), 1);
    assert_eq!(keyboard.front_event().unwrap().code, u32::from(b'A'));
}

#[test]
fn computer_machine_writes_display0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry(
        machine.memory(),
        76,
        computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
        computer_abi::DISPLAY0_BASE,
        computer_abi::DISPLAY0_SIZE,
    );
}

#[test]
fn computer_machine_writes_keyboard0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry_with_irq(
        machine.memory(),
        140,
        computer_abi::COMPUTER_HARDWARE_ID_KEYBOARD0,
        computer_abi::KEYBOARD0_BASE,
        computer_abi::KEYBOARD0_SIZE,
        computer_abi::K16_INTERRUPT_SOURCE_KEYBOARD0,
    );
}

#[test]
fn computer_machine_writes_gpu0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry(
        machine.memory(),
        92,
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::GPU0_SIZE,
    );
}

#[test]
fn computer_display0_mmio_reports_dimensions() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::DISPLAY0_COLUMNS)
            .unwrap(),
        80,
    );
    assert_eq!(
        machine
            .bus
            .load_i32(ComputerMachine::DISPLAY0_ROWS)
            .unwrap(),
        25,
    );
}

#[test]
fn computer_display0_put_byte_updates_snapshot_and_sequence() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine
        .bus
        .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'R'))
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )
        .unwrap();

    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(snapshot.columns, 80);
    assert_eq!(snapshot.rows, 25);
    assert_eq!(snapshot.cursor_x, 1);
    assert_eq!(snapshot.cursor_y, 0);
    assert_eq!(snapshot.sequence, 1);
    assert_eq!(snapshot.cells[0], b'R');
}

#[test]
fn computer_gpu0_blits_guest_ram_to_frame_delta() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    machine
        .write_guest_ram_bytes(0x0100, &[0x00, 0xF8, 0xE0, 0x07, 0x1F, 0x00, 0xFF, 0xFF])
        .unwrap();

    machine.bus.store_i32(ComputerMachine::GPU0_X, 0).unwrap();
    machine.bus.store_i32(ComputerMachine::GPU0_Y, 0).unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::GPU0_RECT_WIDTH, 2)
        .unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::GPU0_RECT_HEIGHT, 2)
        .unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::GPU0_BUFFER_ADDR, 0x0100)
        .unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::GPU0_BUFFER_STRIDE_BYTES, 4)
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::GPU0_COMMAND,
            ComputerMachine::GPU0_COMMAND_BLIT_BUFFER,
        )
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::GPU0_COMMAND,
            ComputerMachine::GPU0_COMMAND_PRESENT,
        )
        .unwrap();

    let frames = machine.drain_gpu0_frames();

    assert_eq!(frames.len(), 1);
    let frame = &frames[0];
    assert_eq!(frame.display_id, 1);
    assert_eq!(frame.width, 320);
    assert_eq!(frame.height, 200);
    assert_eq!(frame.tiles.len(), 1);
    assert_eq!(&frame.tiles[0].payload[0..4], &[0xF8, 0x00, 0x07, 0xE0]);
    assert_eq!(&frame.tiles[0].payload[32..36], &[0x00, 0x1F, 0xFF, 0xFF]);
}

#[test]
fn computer_display0_put_byte_accepts_byte_data_write() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine
        .bus
        .store_u8(ComputerMachine::DISPLAY0_DATA, b'K')
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )
        .unwrap();

    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(snapshot.cursor_x, 1);
    assert_eq!(snapshot.sequence, 1);
    assert_eq!(snapshot.cells[0], b'K');
}

#[test]
fn computer_display0_clear_and_newline_are_deterministic() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine
        .bus
        .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'A'))
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_NEWLINE,
        )
        .unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'B'))
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_CLEAR,
        )
        .unwrap();

    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(snapshot.cursor_x, 0);
    assert_eq!(snapshot.cursor_y, 0);
    assert_eq!(snapshot.sequence, 4);
    assert!(snapshot.cells.iter().all(|cell| *cell == 0));
}

#[test]
fn computer_debug_serial_output_can_be_drained_incrementally() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine
        .bus
        .store_u8(ComputerMachine::DEBUG_WRITE, b'O')
        .unwrap();
    machine
        .bus
        .store_u8(ComputerMachine::DEBUG_WRITE, b'K')
        .unwrap();

    assert_eq!(machine.drain_debug_output_bytes(), b"OK");
    assert_eq!(machine.drain_debug_output_bytes(), b"");
}

#[test]
fn computer_starts_in_reset_status() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(machine.control_status(), ComputerMachine::STATUS_RESET);
    assert_eq!(machine.panic_code(), 0);
}

#[test]
fn computer_machine_writes_machine_profile_v2_boot_info() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(
        read_u32(machine.memory(), 0x00),
        u32::from_le_bytes(*b"RXBI")
    );
    assert_eq!(
        read_u32(machine.memory(), 0x04),
        ComputerMachine::PROFILE_V2_VERSION
    );
    assert_eq!(read_u32(machine.memory(), 0x08), 1024);
    assert_eq!(
        read_u32(machine.memory(), 0x0C),
        ComputerMachine::PROFILE_V2_PAGE_SIZE
    );
    assert_eq!(
        read_u32(machine.memory(), 0x10),
        ComputerMachine::PROFILE_V2_PROGRAM_BASE
    );
    assert_eq!(
        read_u32(machine.memory(), 0x14),
        ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE
    );
    assert_eq!(read_u32(machine.memory(), 0x18), 8);
}

#[test]
fn computer_machine_writes_static_hardware_table_for_mmio_ranges() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_hardware_entry(
        machine.memory(),
        28,
        computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
        computer_abi::CONTROL_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        44,
        computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
        computer_abi::DEBUG_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        60,
        computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
        computer_abi::SERIAL_INPUT_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        76,
        computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
        computer_abi::DISPLAY0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        92,
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        108,
        computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
        computer_abi::STORAGE0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        124,
        computer_abi::COMPUTER_HARDWARE_ID_TIMER0,
        computer_abi::TIMER0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
        crate::k16::K16_INTERRUPT_SOURCE_TIMER0,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        140,
        computer_abi::COMPUTER_HARDWARE_ID_KEYBOARD0,
        computer_abi::KEYBOARD0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
        crate::k16::K16_INTERRUPT_SOURCE_KEYBOARD0,
    );
}

#[test]
fn computer_machine_can_be_created_from_explicit_computer_v1_profile() {
    let profile = ComputerMachineProfile::computer_v1(1024);
    let machine = ComputerMachine::from_profile(profile).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry(
        machine.memory(),
        28,
        computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
        computer_abi::CONTROL_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        44,
        computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
        computer_abi::DEBUG_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        60,
        computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
        computer_abi::SERIAL_INPUT_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        76,
        computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
        computer_abi::DISPLAY0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        92,
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        108,
        computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
        computer_abi::STORAGE0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        124,
        computer_abi::COMPUTER_HARDWARE_ID_TIMER0,
        computer_abi::TIMER0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
        crate::k16::K16_INTERRUPT_SOURCE_TIMER0,
    );
}

#[test]
fn computer_profile_can_expose_storage0_without_attached_media() {
    let profile =
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::storage_port(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
        ));

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

#[test]
fn storage0_absent_media_reports_zero_capacity_and_media_absent_errors() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_VERSION)
            .unwrap(),
        computer_abi::STORAGE_VERSION,
    );
    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_MEDIA_STATUS)
            .unwrap(),
        computer_abi::STORAGE_MEDIA_ABSENT,
    );
    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_CAPACITY_BLOCKS_LOW)
            .unwrap(),
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

    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
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
        machine
            .bus_load_i32(computer_abi::STORAGE0_BYTES_DONE)
            .unwrap(),
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

    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
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

#[test]
fn storage0_file_media_write_blocks_flushes_payload_file() {
    let path = temp_volume_path("machine-storage0-file");
    write_k16_volume(&path, &[0; 512]);
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_k16_volume_file(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            &path,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine.memory_mut().store_u8(512, 0x7E).unwrap();

    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
        )
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_FLUSH,
        )
        .unwrap();

    let bytes = fs::read(&path).unwrap();
    assert_eq!(bytes[16], 0x7E);
    fs::remove_file(path).unwrap();
}

#[test]
fn storage0_file_media_reports_version_and_present_status() {
    let path = temp_volume_path("machine-storage0-file-status");
    write_k16_volume(&path, &[0; 512]);
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_k16_volume_file(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            &path,
        ),
    );
    let machine = ComputerMachine::from_profile(profile).unwrap();

    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_VERSION)
            .unwrap(),
        computer_abi::STORAGE_VERSION,
    );
    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_MEDIA_STATUS)
            .unwrap(),
        computer_abi::STORAGE_MEDIA_PRESENT,
    );
    fs::remove_file(path).unwrap();
}

#[test]
fn storage0_read_blocks_rejects_out_of_bounds_lba() {
    let mut machine = storage0_machine_with_media(vec![0; 512], false);

    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    assert_storage_error(&machine, computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
}

#[test]
fn storage0_read_blocks_rejects_out_of_bounds_guest_buffer() {
    let mut machine = storage0_machine_with_media(vec![0; 512], false);

    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 1800)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    assert_storage_error(&machine, computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
}

#[test]
fn storage0_write_blocks_rejects_read_only_media() {
    let mut machine = storage0_machine_with_media(vec![0; 512], true);

    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
        )
        .unwrap();

    assert_storage_error(&machine, computer_abi::STORAGE_ERROR_WRITE_PROTECTED);
}

#[test]
fn storage0_invalid_command_sets_invalid_command_error() {
    let mut machine = storage0_machine_with_media(vec![0; 512], false);

    machine
        .bus_store_i32(computer_abi::STORAGE0_COMMAND, 99)
        .unwrap();

    assert_storage_error(&machine, computer_abi::STORAGE_ERROR_INVALID_COMMAND);
}

#[test]
fn storage0_read_blocks_rejects_byte_count_overflow() {
    let mut machine = storage0_machine_with_media(vec![0; 512], false);

    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, -1)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    assert_storage_error(&machine, computer_abi::STORAGE_ERROR_BYTE_COUNT_OVERFLOW);
}

#[test]
fn computer_machine_profile_controls_which_hardware_entries_are_visible() {
    let profile = ComputerMachineProfile::new(1024)
        .with_hardware(ComputerHardwareConfig::control(
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            computer_abi::CONTROL_BASE,
        ))
        .with_hardware(ComputerHardwareConfig::debug_serial(
            computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
            computer_abi::DEBUG_BASE,
        ));
    let machine = ComputerMachine::from_profile(profile).unwrap();

    assert_eq!(
        read_u32(machine.memory(), 0x14),
        ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE,
    );
    assert_eq!(read_u32(machine.memory(), 0x18), 2);
    assert_hardware_entry(
        machine.memory(),
        28,
        computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
        computer_abi::CONTROL_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        44,
        computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
        computer_abi::DEBUG_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert!(machine.memory_map().region("display0").is_none());
    assert!(machine.display0_snapshot().is_none());
}

#[test]
fn computer_machine_profile_rejects_invalid_page_sizes() {
    assert_profile_error(
        ComputerMachineProfile {
            page_size: 128,
            ..ComputerMachineProfile::new(1024)
        },
        "computer profile page size 128 is smaller than minimum 256",
    );
    assert_profile_error(
        ComputerMachineProfile {
            page_size: 384,
            ..ComputerMachineProfile::new(1152)
        },
        "computer profile page size 384 is not a power of two",
    );
    assert_profile_error(
        ComputerMachineProfile {
            page_size: 131072,
            ..ComputerMachineProfile::new(131072)
        },
        "computer profile page size 131072 exceeds maximum 65536",
    );
}

#[test]
fn computer_machine_profile_rejects_invalid_program_base() {
    assert_profile_error(
        ComputerMachineProfile {
            program_base: 128,
            ..ComputerMachineProfile::new(1024)
        },
        "computer profile program base 0x00000080 is below first page size 256",
    );
    assert_profile_error(
        ComputerMachineProfile {
            program_base: 384,
            ..ComputerMachineProfile::new(1024)
        },
        "computer profile program base 0x00000180 is not aligned to page size 256",
    );
    assert_profile_error(
        ComputerMachineProfile {
            program_base: 1024,
            ..ComputerMachineProfile::new(1024)
        },
        "computer profile program base 0x00000400 is outside RAM size 1024",
    );
}

#[test]
fn computer_machine_profile_rejects_invalid_hardware_ids() {
    assert_profile_error(
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
            0,
            computer_abi::CONTROL_BASE,
        )),
        "computer hardware id must be non-zero",
    );
    assert_profile_error(
        ComputerMachineProfile::new(1024)
            .with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::debug_serial(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::DEBUG_BASE,
            )),
        "computer hardware id 1 is duplicated",
    );
}

#[test]
fn computer_machine_profile_rejects_invalid_mmio_ranges() {
    assert_profile_error(
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            0,
        )),
        "computer hardware id 1 mmio base must be non-zero",
    );
    assert_profile_error(
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            computer_abi::CONTROL_BASE + 1,
        )),
        "computer hardware id 1 mmio base 0x10000001 is not aligned to page size 256",
    );
    assert_profile_error(
        ComputerMachineProfile {
            page_size: 512,
            program_base: 512,
            ..ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE,
            ))
        },
        "computer hardware id 1 mmio size 256 is not aligned to page size 512",
    );
    assert_profile_error(
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            512,
        )),
        "computer hardware id 1 mmio range 0x00000200..0x00000300 overlaps RAM 0x00000000..0x00000400",
    );
    assert_profile_error(
        ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            u32::MAX - 127,
        )),
        "computer hardware id 1 mmio range 0xffffff80 with size 256 overflows address space",
    );
}

#[test]
fn computer_machine_profile_rejects_overlapping_mmio_ranges() {
    assert_profile_error(
        ComputerMachineProfile::new(1024)
            .with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::debug_serial(
                computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
                computer_abi::CONTROL_BASE,
            )),
        "computer hardware id 2 mmio range 0x10000000..0x10000100 overlaps hardware id 1 range 0x10000000..0x10000100",
    );
}

#[test]
fn computer_machine_profile_rejects_hardware_table_that_does_not_fit_boot_page() {
    let mut profile = ComputerMachineProfile::new(4096);
    for id in 1..=20 {
        profile = profile.with_hardware(ComputerHardwareConfig::control(
            id,
            computer_abi::CONTROL_BASE + (id - 1) * computer_abi::PROFILE_V2_PAGE_SIZE,
        ));
    }

    assert_profile_error(
        profile,
        "computer hardware table with 20 entries does not fit boot page size 256",
    );
}

#[test]
fn computer_machine_rejects_memory_smaller_than_profile_page() {
    let error = match ComputerMachine::new(128) {
        Ok(_) => panic!("computer machine should reject memory smaller than profile page"),
        Err(error) => error,
    };

    assert_eq!(
        error.to_string(),
        "computer memory size 128 is smaller than profile page size 256",
    );
}

#[test]
fn computer_machine_rejects_memory_that_is_not_page_aligned() {
    let error = match ComputerMachine::new(1000) {
        Ok(_) => panic!("computer machine should reject unaligned memory"),
        Err(error) => error,
    };

    assert_eq!(
        error.to_string(),
        "computer memory size 1000 is not a multiple of profile page size 256",
    );
}

#[test]
fn computer_machine_rejects_memory_that_exceeds_u32_address_space() {
    if usize::BITS <= u32::BITS {
        return;
    }
    let memory_size = u32::MAX as usize + 1;
    let error = match ComputerMachine::new(memory_size) {
        Ok(_) => panic!("computer machine should reject memory above u32 address space"),
        Err(error) => error,
    };

    assert_eq!(
        error.to_string(),
        "computer memory size 4294967296 exceeds profile u32 address space",
    );
}

#[test]
fn computer_machine_constants_match_profile_v2_abi() {
    assert_eq!(
        ComputerMachine::PROFILE_V2_BOOT_INFO_MAGIC,
        computer_abi::PROFILE_V2_BOOT_INFO_MAGIC,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_VERSION,
        computer_abi::PROFILE_V2_VERSION,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_PAGE_SIZE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_BOOT_INFO_ADDR,
        computer_abi::PROFILE_V2_BOOT_INFO_ADDR,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_PROGRAM_BASE,
        computer_abi::PROFILE_V2_PROGRAM_BASE,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE,
        computer_abi::PROFILE_V2_BOOT_INFO_SIZE,
    );
    assert_eq!(
        ComputerMachine::PROFILE_V2_HARDWARE_ENTRY_SIZE,
        computer_abi::PROFILE_V2_HARDWARE_ENTRY_SIZE,
    );
    assert_eq!(
        ComputerMachine::HARDWARE_ID_CONTROL,
        computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
    );
    assert_eq!(
        ComputerMachine::HARDWARE_ID_DEBUG,
        computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
    );
    assert_eq!(
        ComputerMachine::HARDWARE_ID_SERIAL_INPUT,
        computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
    );
    assert_eq!(
        ComputerMachine::HARDWARE_ID_DISPLAY0,
        computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
    );
    assert_eq!(
        ComputerMachine::HARDWARE_ID_TIMER0,
        computer_abi::COMPUTER_HARDWARE_ID_TIMER0,
    );
    assert_eq!(ComputerMachine::CONTROL_BASE, computer_abi::CONTROL_BASE);
    assert_eq!(
        ComputerMachine::CONTROL_STATUS,
        computer_abi::CONTROL_STATUS
    );
    assert_eq!(
        ComputerMachine::CONTROL_PANIC_CODE,
        computer_abi::CONTROL_PANIC_CODE,
    );
    assert_eq!(
        ComputerMachine::CONTROL_EXIT_CODE,
        computer_abi::CONTROL_EXIT_CODE,
    );
    assert_eq!(ComputerMachine::CONTROL_YIELD, computer_abi::CONTROL_YIELD);
    assert_eq!(ComputerMachine::CONTROL_SIZE, computer_abi::CONTROL_SIZE);
    assert_eq!(ComputerMachine::DEBUG_BASE, computer_abi::DEBUG_BASE);
    assert_eq!(ComputerMachine::DEBUG_WRITE, computer_abi::DEBUG_WRITE);
    assert_eq!(ComputerMachine::DEBUG_SIZE, computer_abi::DEBUG_SIZE);
    assert_eq!(
        ComputerMachine::SERIAL_INPUT_BASE,
        computer_abi::SERIAL_INPUT_BASE,
    );
    assert_eq!(
        ComputerMachine::SERIAL_INPUT_READY,
        computer_abi::SERIAL_INPUT_READY,
    );
    assert_eq!(
        ComputerMachine::SERIAL_INPUT_READ,
        computer_abi::SERIAL_INPUT_READ,
    );
    assert_eq!(
        ComputerMachine::SERIAL_INPUT_SIZE,
        computer_abi::SERIAL_INPUT_SIZE,
    );
    assert_eq!(ComputerMachine::DISPLAY0_BASE, computer_abi::DISPLAY0_BASE);
    assert_eq!(
        ComputerMachine::DISPLAY0_COLUMNS,
        computer_abi::DISPLAY0_COLUMNS,
    );
    assert_eq!(ComputerMachine::DISPLAY0_ROWS, computer_abi::DISPLAY0_ROWS);
    assert_eq!(
        ComputerMachine::DISPLAY0_CURSOR_X,
        computer_abi::DISPLAY0_CURSOR_X,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_CURSOR_Y,
        computer_abi::DISPLAY0_CURSOR_Y,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_COMMAND,
        computer_abi::DISPLAY0_COMMAND,
    );
    assert_eq!(ComputerMachine::DISPLAY0_DATA, computer_abi::DISPLAY0_DATA);
    assert_eq!(
        ComputerMachine::DISPLAY0_SEQUENCE_LOW,
        computer_abi::DISPLAY0_SEQUENCE_LOW,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_SEQUENCE_HIGH,
        computer_abi::DISPLAY0_SEQUENCE_HIGH,
    );
    assert_eq!(ComputerMachine::DISPLAY0_SIZE, computer_abi::DISPLAY0_SIZE);
    assert_eq!(
        ComputerMachine::DISPLAY0_COMMAND_CLEAR,
        computer_abi::DISPLAY0_COMMAND_CLEAR,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_XY,
        computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_XY,
    );
    assert_eq!(
        ComputerMachine::DISPLAY0_COMMAND_NEWLINE,
        computer_abi::DISPLAY0_COMMAND_NEWLINE,
    );
    assert_eq!(ComputerMachine::GPU0_BASE, computer_abi::GPU0_BASE,);
    assert_eq!(ComputerMachine::GPU0_COMMAND, computer_abi::GPU0_COMMAND,);
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_BLIT_BUFFER,
        computer_abi::GPU0_COMMAND_BLIT_BUFFER,
    );
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_PRESENT,
        computer_abi::GPU0_COMMAND_PRESENT,
    );
    assert_eq!(ComputerMachine::STATUS_RESET, computer_abi::STATUS_RESET);
    assert_eq!(
        ComputerMachine::STATUS_BOOTING,
        computer_abi::STATUS_BOOTING
    );
    assert_eq!(ComputerMachine::STATUS_READY, computer_abi::STATUS_READY);
    assert_eq!(ComputerMachine::STATUS_HALTED, computer_abi::STATUS_HALTED);
    assert_eq!(ComputerMachine::STATUS_PANIC, computer_abi::STATUS_PANIC);
}

#[test]
fn computer_mmio_device_sizes_match_profile_v2_abi() {
    let control = ComputerControlDevice::new();
    let debug = DebugSerialDevice::new();
    let serial_input = SerialInputDevice::new();
    let display = TextDisplayDevice::new();
    let gpu = GpuDevice::new();
    let keyboard = KeyboardDevice::new();

    assert_eq!(control.size(), computer_abi::CONTROL_SIZE);
    assert_eq!(debug.size(), computer_abi::DEBUG_SIZE);
    assert_eq!(serial_input.size(), computer_abi::SERIAL_INPUT_SIZE);
    assert_eq!(display.size(), computer_abi::DISPLAY0_SIZE);
    assert_eq!(gpu.size(), computer_abi::GPU0_SIZE);
    assert_eq!(keyboard.size(), computer_abi::KEYBOARD0_SIZE);
}

#[test]
fn computer_memory_map_describes_ram_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let ram = map.region("ram").unwrap();

    assert_eq!(ram.base, computer_abi::RAM_BASE);
    assert_eq!(ram.size, 1024);
    assert!(ram.readable);
    assert!(ram.writable);
}

#[test]
fn computer_memory_map_describes_control_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let control = map.region("control").unwrap();

    assert_eq!(control.base, computer_abi::CONTROL_BASE);
    assert_eq!(control.size, computer_abi::CONTROL_SIZE);
    assert!(control.readable);
    assert!(control.writable);
}

#[test]
fn computer_memory_map_describes_debug_serial_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let debug = map.region("debug").unwrap();

    assert_eq!(debug.base, computer_abi::DEBUG_BASE);
    assert_eq!(debug.size, computer_abi::DEBUG_SIZE);
    assert!(debug.readable);
    assert!(debug.writable);
}

#[test]
fn computer_memory_map_describes_serial_input_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let serial_input = map.region("serial-input").unwrap();

    assert_eq!(serial_input.base, computer_abi::SERIAL_INPUT_BASE);
    assert_eq!(serial_input.size, computer_abi::SERIAL_INPUT_SIZE);
    assert!(serial_input.readable);
    assert!(serial_input.writable);
}

#[test]
fn computer_memory_map_describes_display0_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let display = map.region("display0").unwrap();

    assert_eq!(display.base, computer_abi::DISPLAY0_BASE);
    assert_eq!(display.size, computer_abi::DISPLAY0_SIZE);
    assert!(display.readable);
    assert!(display.writable);
}

#[test]
fn computer_memory_map_describes_gpu0_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();

    let gpu = map.region("gpu0").unwrap();

    assert_eq!(gpu.base, computer_abi::GPU0_BASE);
    assert_eq!(gpu.size, computer_abi::GPU0_SIZE);
    assert!(gpu.readable);
    assert!(gpu.writable);
}

#[test]
fn computer_memory_map_describes_timer0_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();

    let timer = map.region("timer0").unwrap();

    assert_eq!(timer.base, computer_abi::TIMER0_BASE);
    assert_eq!(timer.size, computer_abi::TIMER0_SIZE);
    assert!(timer.readable);
    assert!(timer.writable);
}

#[test]
fn computer_memory_map_describes_keyboard0_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();

    let keyboard = map.region("keyboard0").unwrap();

    assert_eq!(keyboard.base, computer_abi::KEYBOARD0_BASE);
    assert_eq!(keyboard.size, computer_abi::KEYBOARD0_SIZE);
    assert!(keyboard.readable);
    assert!(keyboard.writable);
}

#[test]
fn computer_snapshot_restores_keyboard0_pending_events() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    machine.push_keyboard_key_up(257, computer_abi::KEYBOARD0_MOD_CONTROL);
    machine.push_keyboard_paste_byte(b'K');

    let snapshot = machine.snapshot_v1().unwrap();
    let restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .unwrap();

    assert_eq!(restored.keyboard0_len(), 2);
    assert_eq!(
        restored
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_EVENT_KIND)
            .unwrap(),
        computer_abi::KEYBOARD0_EVENT_KEY_UP
    );
    assert_eq!(
        restored
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_CODE)
            .unwrap(),
        257
    );
    assert_eq!(
        restored
            .bus
            .load_i32(ComputerMachine::KEYBOARD0_MODIFIERS)
            .unwrap(),
        computer_abi::KEYBOARD0_MOD_CONTROL
    );
}

fn read_u32(memory: &crate::low_machine::MachineMemory, address: u32) -> u32 {
    u32::from_le_bytes(memory.load_i32(address).unwrap().to_le_bytes())
}

fn read_keyboard0_sequence(machine: &ComputerMachine) -> u64 {
    let low = machine
        .bus
        .load_i32(ComputerMachine::KEYBOARD0_SEQUENCE_LOW)
        .unwrap() as u32;
    let high = machine
        .bus
        .load_i32(ComputerMachine::KEYBOARD0_SEQUENCE_HIGH)
        .unwrap() as u32;
    u64::from(low) | (u64::from(high) << 32)
}

fn assert_hardware_entry(
    memory: &crate::low_machine::MachineMemory,
    address: u32,
    id: u32,
    mmio_base: u32,
    mmio_size: u32,
) {
    assert_hardware_entry_with_irq(memory, address, id, mmio_base, mmio_size, 0);
}

fn assert_hardware_entry_with_irq(
    memory: &crate::low_machine::MachineMemory,
    address: u32,
    id: u32,
    mmio_base: u32,
    mmio_size: u32,
    irq_source: u32,
) {
    assert_eq!(read_u32(memory, address), id);
    assert_eq!(read_u32(memory, address + 4), mmio_base);
    assert_eq!(read_u32(memory, address + 8), mmio_size);
    assert_eq!(read_u32(memory, address + 12), irq_source);
}

fn storage0_machine_with_media(media: Vec<u8>, read_only: bool) -> ComputerMachine {
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            media,
            read_only,
        ),
    );
    ComputerMachine::from_profile(profile).unwrap()
}

fn write_k16_volume(path: &std::path::Path, payload: &[u8]) {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"K16VOL");
    bytes.extend_from_slice(&1u16.to_le_bytes());
    bytes.extend_from_slice(&(payload.len() as u64).to_le_bytes());
    bytes.extend_from_slice(payload);
    fs::write(path, bytes).unwrap();
}

fn temp_volume_path(name: &str) -> std::path::PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!(
        "rux-machine-{name}-{}-{nanos}.kv",
        std::process::id()
    ))
}

fn assert_storage_error(machine: &ComputerMachine, error: i32) {
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
        computer_abi::STORAGE_STATUS_ERROR,
    );
    assert_eq!(
        machine.bus_load_i32(computer_abi::STORAGE0_ERROR).unwrap(),
        error,
    );
    assert_eq!(
        machine
            .bus_load_i32(computer_abi::STORAGE0_BYTES_DONE)
            .unwrap(),
        0,
    );
}

fn assert_profile_error(profile: ComputerMachineProfile, expected: &str) {
    let error = match ComputerMachine::from_profile(profile) {
        Ok(_) => panic!("computer machine should reject invalid profile"),
        Err(error) => error,
    };

    assert_eq!(error.to_string(), expected);
}
