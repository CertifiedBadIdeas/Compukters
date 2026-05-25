use rux_vm::computer_machine::ComputerMachine;
use rux_vm::low_image::{encode_image, Function, Image, Instruction};
use rux_vm::low_image_runner::LowImageSignal;
use rux_vm::rux16::Rux16Signal;
use rux_vm::rux_computer::{BootHandoffError, RuxComputerControl, RuxComputerHandle};
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

fn terminal_firmware_image() -> Vec<u8> {
    let image = Image {
        memory_size: 64 * 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 4,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DEBUG_WRITE,
                },
                Instruction::I32Const { dst: 1, value: 82 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 85 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 88 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
        }],
    };
    encode_image(&image).expect("test image encodes")
}

fn display_firmware_image() -> Vec<u8> {
    let image = Image {
        memory_size: 64 * 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 4,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DISPLAY0_DATA,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: ComputerMachine::DISPLAY0_COMMAND,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: i32::from(b'R'),
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::I32Const {
                    dst: 3,
                    value: ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
                },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 2,
                    value: i32::from(b'U'),
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 2,
                    value: i32::from(b'X'),
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
        }],
    };
    encode_image(&image).expect("test image encodes")
}

fn halt_i32_image(exit_code: i32) -> Vec<u8> {
    let image = Image {
        memory_size: 64 * 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 1,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::I32Const {
                    dst: 0,
                    value: exit_code,
                },
                Instruction::ReturnI32 { src: 0 },
            ],
        }],
    };
    encode_image(&image).expect("test image encodes")
}

#[test]
fn rux_computer_handle_boots_firmware_and_exposes_machine_state() {
    let image = terminal_firmware_image();
    let mut handle =
        RuxComputerHandle::create(&image, 64 * 1024, 1_000_000).expect("computer handle creates");

    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0)
    );
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0,
        },
    );
}

#[test]
fn rux_computer_handle_fails_when_memory_is_too_small() {
    let image = terminal_firmware_image();
    let error: String = match RuxComputerHandle::create(&image, 128, 1_000_000) {
        Ok(_) => panic!("computer handle should reject undersized memory"),
        Err(error) => error,
    };

    assert!(
        error.contains("smaller than profile page size"),
        "unexpected error: {error}",
    );
}

#[test]
fn rux_computer_handle_exposes_display0_snapshot() {
    let image = display_firmware_image();
    let mut handle =
        RuxComputerHandle::create(&image, 64 * 1024, 1_000_000).expect("computer handle creates");

    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0)
    );

    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(snapshot.columns, 80);
    assert_eq!(snapshot.rows, 25);
    assert_eq!(snapshot.cursor_x, 3);
    assert_eq!(snapshot.cursor_y, 0);
    assert_eq!(snapshot.sequence, 3);
    assert_eq!(&snapshot.cells[..3], b"RUX");
}

#[test]
fn rux_computer_handle_accepts_storage0_media_and_exposes_snapshot() {
    let image = terminal_firmware_image();
    let media = vec![7; 1024];
    let handle =
        RuxComputerHandle::create_with_storage0_media(&image, 64 * 1024, 1_000_000, media.clone())
            .expect("computer handle creates with storage0 media");

    assert_eq!(
        handle
            .storage0_media_snapshot()
            .expect("storage0 media exists"),
        media,
    );
}

#[test]
fn rux_computer_handle_accepts_storage0_volume_path() {
    let image = terminal_firmware_image();
    let path = temp_volume_path("handle-storage0-path");
    write_rux_volume(&path, &[0; 1024]);

    let handle = RuxComputerHandle::create_with_storage0_path(&image, 64 * 1024, 1_000_000, &path)
        .expect("computer handle creates with storage0 volume path");

    assert!(handle.storage0_media_snapshot().is_none());
    fs::remove_file(path).unwrap();
}

#[test]
fn rux_computer_handle_boot_handoff_replaces_bios_cpu_from_guest_ram() {
    let bios = halt_i32_image(1);
    let next = halt_i32_image(77);
    let image_addr = 4096;
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle.write_guest_ram_bytes(image_addr, &next).unwrap();

    let cpu_id = handle
        .boot_handoff_ruxi_from_guest_ram(image_addr, next.len() as u32, 1_000_000)
        .expect("boot handoff accepts in-RAM RUXI image");

    assert_eq!(cpu_id, 0);
    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(77),
    );
}

#[test]
fn rux_computer_handle_boot_handoff_starts_rux16_from_guest_ram_without_host_decode() {
    let bios = halt_i32_image(1);
    let entry_pc = 4096;
    let program = rux16_words(&[rux16_const4(1, 7), rux16_halt()]);
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    let cpu_id = handle
        .boot_handoff_rux16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM Rux16 program");

    assert_eq!(cpu_id, 0);
    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
}

#[test]
fn rux_computer_handle_rux16_firmware_writes_debug_and_control_mmio() {
    let bios = halt_i32_image(1);
    let entry_pc = 4096;
    let program = rux16_words(&rux16_mmio_firmware_words());
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    handle
        .boot_handoff_rux16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM Rux16 firmware");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
}

#[test]
fn rux_computer_handle_boots_rux16_directly_from_bios_flash() {
    let bios = rux16_words(&rux16_mmio_firmware_words());
    let mut handle = RuxComputerHandle::create_rux16_bios_flash(&bios, 64 * 1024, 128)
        .expect("Rux16 BIOS flash computer creates");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
}

#[test]
fn rux_computer_handle_rejects_empty_rux16_bios_flash() {
    let error = match RuxComputerHandle::create_rux16_bios_flash(&[], 64 * 1024, 128) {
        Ok(_) => panic!("empty Rux16 BIOS flash unexpectedly created a computer"),
        Err(error) => error,
    };

    assert!(
        error.contains("Rux16 BIOS flash is empty"),
        "unexpected error: {error}",
    );
}

#[test]
fn rux_computer_handle_rux16_bios_flash_is_read_only() {
    let mut words = Vec::new();
    words.extend(rux16_const32(0, ComputerMachine::RUX16_BIOS_FLASH_BASE));
    words.extend(rux16_const32(1, 0x1234));
    words.push(rux16_store32(0, 1));
    words.push(rux16_halt());
    let bios = rux16_words(&words);
    let mut handle = RuxComputerHandle::create_rux16_bios_flash(&bios, 64 * 1024, 128)
        .expect("Rux16 BIOS flash computer creates");

    let error = handle
        .run_rux16_until_signal()
        .expect_err("flash write traps");

    assert!(
        error.contains("BIOS flash is read-only"),
        "unexpected error: {error}",
    );
}

#[test]
fn rux_computer_handle_rux16_bios_flash_reads_storage0_block_into_ram() {
    let bios = rux16_words(&rux16_storage_read_bios_words());
    let mut media = vec![0; 512];
    media[0..3].copy_from_slice(b"RUX");
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &bios,
        64 * 1024,
        256,
        media,
    )
    .expect("Rux16 BIOS flash computer creates with storage0 media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 2,
        },
    );
}

#[test]
fn rux_computer_handle_boot_handoff_rejects_empty_image_and_keeps_bios_cpu() {
    let bios = halt_i32_image(1);
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");

    let error = handle
        .boot_handoff_ruxi_from_guest_ram(4096, 0, 1_000_000)
        .expect_err("empty boot handoff image is rejected");

    assert_eq!(error, BootHandoffError::EmptyImage);
    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(1),
    );
}

#[test]
fn rux_computer_handle_boot_handoff_rejects_ram_range_out_of_bounds_and_keeps_bios_cpu() {
    let bios = halt_i32_image(1);
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");

    let error = handle
        .boot_handoff_ruxi_from_guest_ram(64 * 1024 - 1, 2, 1_000_000)
        .expect_err("out-of-bounds boot handoff RAM range is rejected");

    assert_eq!(
        error,
        BootHandoffError::RamRangeOutOfBounds {
            image_addr: 64 * 1024 - 1,
            image_len: 2,
            ram_len: 64 * 1024,
        },
    );
    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(1),
    );
}

#[test]
fn rux_computer_handle_boot_handoff_rejects_invalid_ruxi_and_keeps_bios_cpu() {
    let bios = halt_i32_image(1);
    let image_addr = 4096;
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle
        .write_guest_ram_bytes(image_addr, b"NOPE")
        .expect("test writes invalid image bytes into RAM");

    let error = handle
        .boot_handoff_ruxi_from_guest_ram(image_addr, 4, 1_000_000)
        .expect_err("invalid boot handoff RUXI image is rejected");

    assert!(
        matches!(&error, BootHandoffError::InvalidImage(message) if message.contains("magic")),
        "unexpected error: {error}",
    );
    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(1),
    );
}

fn write_rux_volume(path: &std::path::Path, payload: &[u8]) {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"RUXVOL");
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
        "rux-computer-{name}-{}-{nanos}.ruxvol",
        std::process::id()
    ))
}

fn rux16_words(words: &[u16]) -> Vec<u8> {
    words
        .iter()
        .flat_map(|word| word.to_le_bytes())
        .collect::<Vec<_>>()
}

fn rux16_const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn rux16_const32(dst: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(dst) << 8),
        value as u16,
        (value >> 16) as u16,
    ]
}

fn rux16_store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn rux16_load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn rux16_load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn rux16_mmio_firmware_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(rux16_const32(0, ComputerMachine::DEBUG_WRITE));
    words.extend(rux16_const32(1, u32::from(b'R')));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(1, u32::from(b'U')));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(1, u32::from(b'X')));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.extend(rux16_const32(1, 0x16));
    words.push(rux16_store32(0, 1));
    words.push(rux16_halt());
    words
}

fn rux16_storage_read_bios_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_LBA_LOW));
    words.push(rux16_const4(1, 0));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_LBA_HIGH));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_BLOCK_COUNT));
    words.push(rux16_const4(1, 1));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_BUFFER_ADDR));
    words.extend(rux16_const32(1, 512));
    words.push(rux16_store32(0, 1));
    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_COMMAND));
    words.push(rux16_const4(1, 1));
    words.push(rux16_store32(0, 1));

    words.extend(rux16_const32(0, 512));
    words.extend(rux16_const32(3, ComputerMachine::DEBUG_WRITE));
    words.push(rux16_const4(4, 1));
    words.push(rux16_load8(2, 0));
    words.push(rux16_store32(3, 2));
    words.push(rux16_add(0, 0, 4));
    words.push(rux16_load8(2, 0));
    words.push(rux16_store32(3, 2));
    words.push(rux16_add(0, 0, 4));
    words.push(rux16_load8(2, 0));
    words.push(rux16_store32(3, 2));

    words.extend(rux16_const32(0, ComputerMachine::STORAGE0_STATUS));
    words.push(rux16_load32(2, 0));
    words.extend(rux16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(rux16_store32(0, 2));
    words.push(rux16_halt());
    words
}

fn rux16_add(dst: u8, lhs: u8, rhs: u8) -> u16 {
    0x2000 | (u16::from(dst) << 8) | (u16::from(lhs) << 4) | u16::from(rhs)
}

fn rux16_halt() -> u16 {
    0x0001
}
