use super::ComputerMachine;
use crate::computer::devices::{
    ComputerControlDevice, DebugSerialDevice, GpuDevice, KeyboardDevice, MmuControlDevice,
    SerialInputDevice,
};
use crate::computer::profile::{ComputerHardwareConfig, ComputerMachineProfile};
use crate::computer_abi;
use crate::k16::{K16AddressMode, K16PrivilegeMode, K16Signal};
use crate::low_bus::MmioDevice;
use crate::mmu::MmuMapFlags;
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn computer_machine_owns_shared_physical_ram() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.memory_mut().store_i32(128, 42).unwrap();

    assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
}

#[test]
fn computer_machine_mmu_defaults_to_physical_kernel_boot_execution() {
    let bios = k16_words(&[k16_halt()]);
    let (mut machine, boot_cpu) = ComputerMachine::from_k16_bios_flash(&bios, 1024, 8).unwrap();

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
}

#[test]
fn computer_machine_mmu_runs_boot_cpu_through_virtual_pc_and_data_mapping() {
    const RAM_SIZE: usize = 0x3000;
    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let program = k16_words(&[
        k16_const32(1, 0x8000)[0],
        k16_const32(1, 0x8000)[1],
        k16_const32(1, 0x8000)[2],
        k16_load32(2, 1),
        k16_const4(3, 4),
        k16_add(1, 1, 3)[0],
        k16_add(1, 1, 3)[1],
        k16_store32(1, 2),
        k16_halt(),
    ]);
    machine.write_guest_ram_bytes(0, &program).unwrap();
    machine.bus_store_i32(0x1000, 0x0102_0304).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(address_space, 0x4000, 0, 1, MmuMapFlags::EXECUTABLE)
        .unwrap();
    machine
        .map_mmu_pages(address_space, 0x8000, 0x1000, 1, MmuMapFlags::WRITABLE)
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(0x4000, 16);
    machine
        .set_k16_cpu_address_mode(boot_cpu, K16AddressMode::Translated { address_space })
        .unwrap();
    machine
        .set_k16_cpu_privilege_mode(boot_cpu, K16PrivilegeMode::Kernel)
        .unwrap();

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(machine.bus_load_i32(0x1004).unwrap(), 0x0102_0304);
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn computer_machine_mmu_user_mode_faults_on_supervisor_only_mapping() {
    const RAM_SIZE: usize = 0x3000;
    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    machine
        .write_guest_ram_bytes(0, &k16_words(&[k16_halt()]))
        .unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(address_space, 0x4000, 0, 1, MmuMapFlags::EXECUTABLE)
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(0x4000, 16);
    machine
        .set_k16_cpu_address_mode(boot_cpu, K16AddressMode::Translated { address_space })
        .unwrap();
    machine
        .set_k16_cpu_privilege_mode(boot_cpu, K16PrivilegeMode::User)
        .unwrap();

    let error = machine
        .run_boot_k16_until_signal(boot_cpu)
        .expect_err("user mode should not fetch supervisor-only mapping");
    assert!(error.contains("MMU permission fault"));
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
}

#[test]
fn computer_machine_mmu0_guest_kernel_enters_translated_user_execution() {
    const RAM_SIZE: usize = 0x4000;
    const KERNEL_PC: u32 = 0x0100;
    const USER_VIRTUAL_PC: u32 = 0x4000;
    const USER_VIRTUAL_DATA: u32 = 0x8000;
    const USER_STACK_TOP: u32 = 0x9000;
    const USER_CODE_PHYSICAL: u32 = 0x1000;
    const USER_DATA_PHYSICAL: u32 = 0x2000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let kernel = k16_words(&[
        k16_const32(1, ComputerMachine::MMU0_BASE)[0],
        k16_const32(1, ComputerMachine::MMU0_BASE)[1],
        k16_const32(1, ComputerMachine::MMU0_BASE)[2],
        k16_const4(3, 12),
        k16_add(7, 1, 3)[0],
        k16_add(7, 1, 3)[1],
        k16_const4(2, ComputerMachine::MMU0_COMMAND_CREATE_ADDRESS_SPACE as u8),
        k16_store32(7, 2),
        k16_const32(3, 44)[0],
        k16_const32(3, 44)[1],
        k16_const32(3, 44)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_load32(5, 4),
        k16_const32(3, 16)[0],
        k16_const32(3, 16)[1],
        k16_const32(3, 16)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_store32(4, 5),
        k16_const32(3, 20)[0],
        k16_const32(3, 20)[1],
        k16_const32(3, 20)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_VIRTUAL_PC)[0],
        k16_const32(6, USER_VIRTUAL_PC)[1],
        k16_const32(6, USER_VIRTUAL_PC)[2],
        k16_store32(4, 6),
        k16_const32(3, 24)[0],
        k16_const32(3, 24)[1],
        k16_const32(3, 24)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_CODE_PHYSICAL)[0],
        k16_const32(6, USER_CODE_PHYSICAL)[1],
        k16_const32(6, USER_CODE_PHYSICAL)[2],
        k16_store32(4, 6),
        k16_const32(3, 28)[0],
        k16_const32(3, 28)[1],
        k16_const32(3, 28)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const4(6, 1),
        k16_store32(4, 6),
        k16_const32(3, 32)[0],
        k16_const32(3, 32)[1],
        k16_const32(3, 32)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const4(6, 5),
        k16_store32(4, 6),
        k16_const4(2, ComputerMachine::MMU0_COMMAND_MAP_PAGES as u8),
        k16_store32(7, 2),
        k16_const32(3, 20)[0],
        k16_const32(3, 20)[1],
        k16_const32(3, 20)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_VIRTUAL_DATA)[0],
        k16_const32(6, USER_VIRTUAL_DATA)[1],
        k16_const32(6, USER_VIRTUAL_DATA)[2],
        k16_store32(4, 6),
        k16_const32(3, 24)[0],
        k16_const32(3, 24)[1],
        k16_const32(3, 24)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_DATA_PHYSICAL)[0],
        k16_const32(6, USER_DATA_PHYSICAL)[1],
        k16_const32(6, USER_DATA_PHYSICAL)[2],
        k16_store32(4, 6),
        k16_const32(3, 32)[0],
        k16_const32(3, 32)[1],
        k16_const32(3, 32)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const4(6, 3),
        k16_store32(4, 6),
        k16_const4(2, ComputerMachine::MMU0_COMMAND_MAP_PAGES as u8),
        k16_store32(7, 2),
        k16_const32(3, 36)[0],
        k16_const32(3, 36)[1],
        k16_const32(3, 36)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_VIRTUAL_PC)[0],
        k16_const32(6, USER_VIRTUAL_PC)[1],
        k16_const32(6, USER_VIRTUAL_PC)[2],
        k16_store32(4, 6),
        k16_const32(3, 40)[0],
        k16_const32(3, 40)[1],
        k16_const32(3, 40)[2],
        k16_add(4, 1, 3)[0],
        k16_add(4, 1, 3)[1],
        k16_const32(6, USER_STACK_TOP)[0],
        k16_const32(6, USER_STACK_TOP)[1],
        k16_const32(6, USER_STACK_TOP)[2],
        k16_store32(4, 6),
        k16_const4(
            2,
            ComputerMachine::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE as u8,
        ),
        k16_store32(7, 2),
        k16_halt(),
    ]);
    let user = k16_words(&[
        k16_const32(1, USER_VIRTUAL_DATA)[0],
        k16_const32(1, USER_VIRTUAL_DATA)[1],
        k16_const32(1, USER_VIRTUAL_DATA)[2],
        k16_const32(2, 0x1122_3344)[0],
        k16_const32(2, 0x1122_3344)[1],
        k16_const32(2, 0x1122_3344)[2],
        k16_store32(1, 2),
        k16_halt(),
    ]);
    machine.write_guest_ram_bytes(KERNEL_PC, &kernel).unwrap();
    machine
        .write_guest_ram_bytes(USER_CODE_PHYSICAL, &user)
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 256);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine.bus_load_i32(USER_DATA_PHYSICAL).unwrap(),
        0x1122_3344
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn computer_machine_mmu0_copies_from_translated_user_buffer_across_pages() {
    const RAM_SIZE: usize = 0x6000;
    const KERNEL_PC: u32 = 0x0000;
    const KERNEL_BUFFER: u32 = 0x0100;
    const USER_VIRTUAL_BASE: u32 = 0x4000;
    const USER_VIRTUAL_BUFFER: u32 = 0x4ff8;
    const USER_PHYSICAL_BASE: u32 = 0x2000;
    const USER_PHYSICAL_BUFFER: u32 = 0x2ff8;
    const BYTE_COUNT: u32 = 16;
    const SOURCE_BYTES: &[u8] = b"abcdefghijklmnop";

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_BASE,
            USER_PHYSICAL_BASE,
            2,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    machine
        .write_guest_ram_bytes(USER_PHYSICAL_BUFFER, SOURCE_BYTES)
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        USER_VIRTUAL_BUFFER,
        KERNEL_BUFFER,
        BYTE_COUNT,
        ComputerMachine::MMU0_COMMAND_COPY_FROM_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine
            .read_guest_ram_bytes(KERNEL_BUFFER, BYTE_COUNT)
            .unwrap(),
        SOURCE_BYTES,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_STATUS).unwrap(),
        ComputerMachine::MMU0_STATUS_DONE,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_RESULT).unwrap() as u32,
        BYTE_COUNT,
    );
}

#[test]
fn computer_machine_mmu0_copies_to_translated_user_buffer_across_pages() {
    const RAM_SIZE: usize = 0x6000;
    const KERNEL_PC: u32 = 0x0000;
    const KERNEL_BUFFER: u32 = 0x0100;
    const USER_VIRTUAL_BASE: u32 = 0x4000;
    const USER_VIRTUAL_BUFFER: u32 = 0x4ff8;
    const USER_PHYSICAL_BASE: u32 = 0x2000;
    const USER_PHYSICAL_BUFFER: u32 = 0x2ff8;
    const BYTE_COUNT: u32 = 16;
    const SOURCE_BYTES: &[u8] = b"ABCDEFGHIJKLMNOP";

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_BASE,
            USER_PHYSICAL_BASE,
            2,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_BUFFER, SOURCE_BYTES)
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        USER_VIRTUAL_BUFFER,
        KERNEL_BUFFER,
        BYTE_COUNT,
        ComputerMachine::MMU0_COMMAND_COPY_TO_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine
            .read_guest_ram_bytes(USER_PHYSICAL_BUFFER, BYTE_COUNT)
            .unwrap(),
        SOURCE_BYTES,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_STATUS).unwrap(),
        ComputerMachine::MMU0_STATUS_DONE,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_RESULT).unwrap() as u32,
        BYTE_COUNT,
    );
}

#[test]
fn computer_machine_mmu0_copy_reports_invalid_address_space() {
    const RAM_SIZE: usize = 0x2000;
    const KERNEL_PC: u32 = 0x0000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        99,
        0x4000,
        0x0100,
        4,
        ComputerMachine::MMU0_COMMAND_COPY_FROM_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_mmu0_error(&machine, ComputerMachine::MMU0_ERROR_INVALID_ADDRESS_SPACE);
}

#[test]
fn computer_machine_mmu0_copy_to_user_reports_permission_fault_for_read_only_mapping() {
    const RAM_SIZE: usize = 0x4000;
    const KERNEL_PC: u32 = 0x0000;
    const KERNEL_BUFFER: u32 = 0x0100;
    const USER_VIRTUAL_BASE: u32 = 0x4000;
    const USER_PHYSICAL_BASE: u32 = 0x2000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_BASE,
            USER_PHYSICAL_BASE,
            1,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_BUFFER, b"ABCD")
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        USER_VIRTUAL_BASE,
        KERNEL_BUFFER,
        4,
        ComputerMachine::MMU0_COMMAND_COPY_TO_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_mmu0_error(&machine, ComputerMachine::MMU0_ERROR_TRANSLATION_FAULT);
    assert_eq!(
        machine.read_guest_ram_bytes(USER_PHYSICAL_BASE, 4).unwrap(),
        [0, 0, 0, 0],
    );
}

#[test]
fn computer_machine_mmu0_copy_from_user_reports_physical_out_of_bounds() {
    const RAM_SIZE: usize = 0x4000;
    const KERNEL_PC: u32 = 0x0000;
    const USER_VIRTUAL_BASE: u32 = 0x4000;
    const USER_PHYSICAL_BASE: u32 = 0x2000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_BASE,
            USER_PHYSICAL_BASE,
            1,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        USER_VIRTUAL_BASE,
        (RAM_SIZE as u32) - 2,
        4,
        ComputerMachine::MMU0_COMMAND_COPY_FROM_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_mmu0_error(&machine, ComputerMachine::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS);
}

#[test]
fn computer_machine_mmu0_copy_reports_byte_count_overflow() {
    const RAM_SIZE: usize = 0x4000;
    const KERNEL_PC: u32 = 0x0000;
    const USER_VIRTUAL_BASE: u32 = 0x4000;
    const USER_PHYSICAL_BASE: u32 = 0x2000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_BASE,
            USER_PHYSICAL_BASE,
            1,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        USER_VIRTUAL_BASE,
        u32::MAX - 1,
        4,
        ComputerMachine::MMU0_COMMAND_COPY_FROM_USER,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_mmu0_error(&machine, ComputerMachine::MMU0_ERROR_BYTE_COUNT_OVERFLOW);
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
fn computer_machine_omits_display0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert!(machine.memory_map().region("display0").is_none());
}

#[test]
fn computer_machine_writes_keyboard0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry_with_irq(
        machine.memory(),
        124,
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
        76,
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::GPU0_SIZE,
    );
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
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        92,
        computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
        computer_abi::STORAGE0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        108,
        computer_abi::COMPUTER_HARDWARE_ID_TIMER0,
        computer_abi::TIMER0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
        crate::k16::K16_INTERRUPT_SOURCE_TIMER0,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        124,
        computer_abi::COMPUTER_HARDWARE_ID_KEYBOARD0,
        computer_abi::KEYBOARD0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
        crate::k16::K16_INTERRUPT_SOURCE_KEYBOARD0,
    );
    assert_hardware_entry(
        machine.memory(),
        140,
        computer_abi::COMPUTER_HARDWARE_ID_MMU0,
        computer_abi::MMU0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
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
        computer_abi::COMPUTER_HARDWARE_ID_GPU0,
        computer_abi::GPU0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry(
        machine.memory(),
        92,
        computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
        computer_abi::STORAGE0_BASE,
        computer_abi::PROFILE_V2_PAGE_SIZE,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        108,
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
    assert_eq!(ComputerMachine::MMU0_BASE, computer_abi::MMU0_BASE);
    assert_eq!(ComputerMachine::MMU0_VERSION, computer_abi::MMU0_VERSION);
    assert_eq!(ComputerMachine::MMU0_STATUS, computer_abi::MMU0_STATUS);
    assert_eq!(ComputerMachine::MMU0_ERROR, computer_abi::MMU0_ERROR);
    assert_eq!(ComputerMachine::MMU0_COMMAND, computer_abi::MMU0_COMMAND);
    assert_eq!(
        ComputerMachine::MMU0_ADDRESS_SPACE,
        computer_abi::MMU0_ADDRESS_SPACE,
    );
    assert_eq!(
        ComputerMachine::MMU0_VIRTUAL_START,
        computer_abi::MMU0_VIRTUAL_START,
    );
    assert_eq!(
        ComputerMachine::MMU0_PHYSICAL_START,
        computer_abi::MMU0_PHYSICAL_START,
    );
    assert_eq!(
        ComputerMachine::MMU0_PAGE_COUNT,
        computer_abi::MMU0_PAGE_COUNT,
    );
    assert_eq!(
        ComputerMachine::MMU0_BYTE_COUNT,
        computer_abi::MMU0_BYTE_COUNT,
    );
    assert_eq!(ComputerMachine::MMU0_FLAGS, computer_abi::MMU0_FLAGS);
    assert_eq!(ComputerMachine::MMU0_ENTRY_PC, computer_abi::MMU0_ENTRY_PC);
    assert_eq!(
        ComputerMachine::MMU0_STACK_POINTER,
        computer_abi::MMU0_STACK_POINTER,
    );
    assert_eq!(ComputerMachine::MMU0_RESULT, computer_abi::MMU0_RESULT);
    assert_eq!(ComputerMachine::MMU0_SIZE, computer_abi::MMU0_SIZE);
    assert_eq!(
        ComputerMachine::MMU0_ERROR_NONE,
        computer_abi::MMU0_ERROR_NONE
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_INVALID_COMMAND,
        computer_abi::MMU0_ERROR_INVALID_COMMAND,
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_INVALID_ARGUMENT,
        computer_abi::MMU0_ERROR_INVALID_ARGUMENT,
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_INVALID_ADDRESS_SPACE,
        computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE,
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_TRANSLATION_FAULT,
        computer_abi::MMU0_ERROR_TRANSLATION_FAULT,
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS,
        computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS,
    );
    assert_eq!(
        ComputerMachine::MMU0_ERROR_BYTE_COUNT_OVERFLOW,
        computer_abi::MMU0_ERROR_BYTE_COUNT_OVERFLOW,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_CREATE_ADDRESS_SPACE,
        computer_abi::MMU0_COMMAND_CREATE_ADDRESS_SPACE,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_MAP_PAGES,
        computer_abi::MMU0_COMMAND_MAP_PAGES,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_PROTECT_PAGES,
        computer_abi::MMU0_COMMAND_PROTECT_PAGES,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE,
        computer_abi::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_COPY_FROM_USER,
        computer_abi::MMU0_COMMAND_COPY_FROM_USER,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_COPY_TO_USER,
        computer_abi::MMU0_COMMAND_COPY_TO_USER,
    );
}

#[test]
fn computer_mmio_device_sizes_match_profile_v2_abi() {
    let control = ComputerControlDevice::new();
    let debug = DebugSerialDevice::new();
    let serial_input = SerialInputDevice::new();
    let gpu = GpuDevice::new();
    let keyboard = KeyboardDevice::new();
    let mmu = MmuControlDevice::new();

    assert_eq!(control.size(), computer_abi::CONTROL_SIZE);
    assert_eq!(debug.size(), computer_abi::DEBUG_SIZE);
    assert_eq!(serial_input.size(), computer_abi::SERIAL_INPUT_SIZE);
    assert_eq!(gpu.size(), computer_abi::GPU0_SIZE);
    assert_eq!(keyboard.size(), computer_abi::KEYBOARD0_SIZE);
    assert_eq!(mmu.size(), computer_abi::MMU0_SIZE);
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
fn computer_memory_map_describes_mmu0_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();

    let mmu = map.region("mmu0").unwrap();

    assert_eq!(mmu.base, computer_abi::MMU0_BASE);
    assert_eq!(mmu.size, computer_abi::MMU0_SIZE);
    assert!(mmu.readable);
    assert!(mmu.writable);
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

fn k16_words(words: &[u16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(words.len() * 2);
    for word in words {
        bytes.extend_from_slice(&word.to_le_bytes());
    }
    bytes
}

fn k16_const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn k16_const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn k16_nop() -> u16 {
    0x0000
}

fn k16_add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn k16_load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn k16_store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn k16_halt() -> u16 {
    0x0001
}

fn submit_mmu0_command(
    machine: &mut ComputerMachine,
    address_space: u32,
    virtual_start: u32,
    physical_start: u32,
    byte_count: u32,
    command: i32,
) {
    machine
        .bus_store_i32(ComputerMachine::MMU0_ADDRESS_SPACE, address_space as i32)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::MMU0_VIRTUAL_START, virtual_start as i32)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::MMU0_PHYSICAL_START, physical_start as i32)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::MMU0_BYTE_COUNT, byte_count as i32)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::MMU0_COMMAND, command)
        .unwrap();
}

fn assert_mmu0_error(machine: &ComputerMachine, error: i32) {
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_STATUS).unwrap(),
        ComputerMachine::MMU0_STATUS_ERROR,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_ERROR).unwrap(),
        error,
    );
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
