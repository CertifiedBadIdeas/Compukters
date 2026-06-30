use super::ComputerMachine;
use crate::computer::devices::{
    ComputerControlDevice, DebugSerialDevice, GpuDevice, KeyboardDevice, MmuControlDevice,
    SerialInputDevice,
};
use crate::computer::profile::{ComputerHardwareConfig, ComputerMachineProfile};
use crate::computer_abi;
use crate::display::DisplayFrameOperation;
use crate::k16::{
    K16AddressMode, K16PrivilegeMode, K16Signal, K16_CSR_TRAP_FRAME_INDEX,
    K16_CSR_TRAP_FRAME_REGISTER, K16_CSR_TRAP_RESUME_PC, K16_CSR_TRAP_VECTOR,
    K16_STACK_POINTER_REGISTER,
};
use crate::low_bus::MmioDevice;
use crate::mmu::MmuMapFlags;
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn computer_machine_uses_device_id_facade_instead_of_per_device_id_fields() {
    let source = fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/computer/machine.rs"),
    )
    .expect("machine.rs source is readable");
    let struct_body = source
        .split("pub struct ComputerMachine {")
        .nth(1)
        .and_then(|tail| tail.split("enum ComputerCpuContext").next())
        .expect("ComputerMachine struct body is present");

    assert!(struct_body.contains("devices: ComputerDeviceIds"));
    for field in [
        "control_device_id",
        "debug_device_id",
        "serial_input_device_id",
        "gpu0_device_id",
        "storage0_device_id",
        "timer0_device_id",
        "keyboard0_device_id",
        "mmu0_device_id",
        "bios_flash_device_id",
    ] {
        assert!(
            !struct_body.contains(field),
            "ComputerMachine should store typed MMIO ids through ComputerDeviceIds, not {field}",
        );
    }
}

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
fn computer_machine_boot_runtime_reuses_cached_decoder() {
    let program = k16_words(&[
        k16_const4(1, 3),
        k16_const32(2, u32::MAX)[0],
        k16_const32(2, u32::MAX)[1],
        k16_const32(2, u32::MAX)[2],
        k16_add(1, 1, 2)[0],
        k16_add(1, 1, 2)[1],
        k16_branch_if_nonzero(1, -3),
        k16_halt(),
    ]);
    let (mut machine, boot_cpu) = ComputerMachine::from_k16_bios_flash(&program, 1024, 64).unwrap();

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    let stats = machine.k16_decode_cache_stats_for_tests(boot_cpu).unwrap();
    assert!(stats.hits > 0, "{stats:?}");
    assert!(stats.entries > 0, "{stats:?}");
    let snapshot_stats = machine.stats_snapshot().decode_cache;
    assert_eq!(snapshot_stats.entries, stats.entries as u64);
    assert_eq!(snapshot_stats.hits, stats.hits);
    assert_eq!(snapshot_stats.misses, stats.misses);
}

#[test]
fn computer_machine_boot_handoff_resets_cached_decoder() {
    let program = k16_words(&[
        k16_const4(1, 3),
        k16_const32(2, u32::MAX)[0],
        k16_const32(2, u32::MAX)[1],
        k16_const32(2, u32::MAX)[2],
        k16_add(1, 1, 2)[0],
        k16_add(1, 1, 2)[1],
        k16_branch_if_nonzero(1, -3),
        k16_halt(),
    ]);
    let (mut machine, boot_cpu) = ComputerMachine::from_k16_bios_flash(&program, 1024, 64).unwrap();
    machine.run_boot_k16_until_signal(boot_cpu).unwrap();
    assert!(
        machine
            .k16_decode_cache_stats_for_tests(boot_cpu)
            .unwrap()
            .entries
            > 0
    );

    let replacement = k16_words(&[k16_halt()]);
    machine.write_guest_ram_bytes(0x80, &replacement).unwrap();
    let boot_cpu = machine
        .boot_handoff_k16_from_ram(0x80, replacement.len() as u32, 8)
        .unwrap();

    let stats = machine.k16_decode_cache_stats_for_tests(boot_cpu).unwrap();
    assert_eq!(stats.entries, 0);
    assert_eq!(stats.hits, 0);
    assert_eq!(stats.misses, 0);
}

#[test]
fn computer_machine_mmu0_mapping_command_clears_cached_decoder() {
    let mut machine = ComputerMachine::new(0x3000).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    let mut words = vec![
        k16_const4(1, 3),
        k16_const32(2, u32::MAX)[0],
        k16_const32(2, u32::MAX)[1],
        k16_const32(2, u32::MAX)[2],
        k16_add(1, 1, 2)[0],
        k16_add(1, 1, 2)[1],
        k16_branch_if_nonzero(1, -3),
    ];
    append_k16_store_const32(
        &mut words,
        ComputerMachine::MMU0_ADDRESS_SPACE,
        address_space.raw(),
    );
    append_k16_store_const32(&mut words, ComputerMachine::MMU0_VIRTUAL_START, 0x4000);
    append_k16_store_const32(&mut words, ComputerMachine::MMU0_PHYSICAL_START, 0);
    append_k16_store_const32(&mut words, ComputerMachine::MMU0_PAGE_COUNT, 1);
    append_k16_store_const32(
        &mut words,
        ComputerMachine::MMU0_FLAGS,
        computer_abi::MMU0_FLAG_EXECUTABLE as u32,
    );
    append_k16_store_const32(
        &mut words,
        ComputerMachine::MMU0_COMMAND,
        computer_abi::MMU0_COMMAND_MAP_PAGES as u32,
    );
    words.push(k16_halt());
    machine
        .write_guest_ram_bytes(0, &k16_words(&words))
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(0, 256);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    let stats = machine.k16_decode_cache_stats_for_tests(boot_cpu).unwrap();
    assert_eq!(stats.entries, 1, "{stats:?}");
    assert_eq!(stats.hits, 0, "{stats:?}");
    assert_eq!(stats.misses, 1, "{stats:?}");
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
fn computer_machine_mmu0_can_override_trap_return_to_physical_context() {
    const RAM_SIZE: usize = 0x4000;
    const SETUP_PC: u32 = 0x0100;
    const HANDLER_PC: u32 = 0x0200;
    const PARENT_PC: u32 = 0x0300;
    const USER_PHYSICAL_PC: u32 = 0x1000;
    const USER_VIRTUAL_PC: u32 = 0x4000;
    const PARENT_PROOF: u32 = 0x3000;
    const KERNEL_STACK_TOP: u32 = 0x3800;
    const USER_STACK_TOP: u32 = 0x9000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let setup = k16_words(&[
        k16_const32(1, HANDLER_PC)[0],
        k16_const32(1, HANDLER_PC)[1],
        k16_const32(1, HANDLER_PC)[2],
        k16_write_csr(K16_CSR_TRAP_VECTOR, 1),
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[0],
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[1],
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[2],
        k16_wait(),
    ]);
    let handler = k16_words(&[
        k16_const32(1, PARENT_PC)[0],
        k16_const32(1, PARENT_PC)[1],
        k16_const32(1, PARENT_PC)[2],
        k16_write_csr(K16_CSR_TRAP_RESUME_PC, 1),
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[0],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[1],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[2],
        k16_const4(
            2,
            ComputerMachine::MMU0_COMMAND_SET_TRAP_RETURN_PHYSICAL as u8,
        ),
        k16_store32(1, 2),
        k16_iret(),
    ]);
    let parent = k16_words(&[
        k16_const32(1, PARENT_PROOF)[0],
        k16_const32(1, PARENT_PROOF)[1],
        k16_const32(1, PARENT_PROOF)[2],
        k16_const4(2, 1),
        k16_store32(1, 2),
        k16_halt(),
    ]);
    let user = k16_words(&[k16_syscall(1), k16_halt()]);
    machine.write_guest_ram_bytes(SETUP_PC, &setup).unwrap();
    machine.write_guest_ram_bytes(HANDLER_PC, &handler).unwrap();
    machine.write_guest_ram_bytes(PARENT_PC, &parent).unwrap();
    machine
        .write_guest_ram_bytes(USER_PHYSICAL_PC, &user)
        .unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_PC,
            USER_PHYSICAL_PC,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(SETUP_PC, 64);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Wait,
    );
    machine
        .k16_cpu_mut(boot_cpu)
        .unwrap()
        .enter_user_address_space(address_space, USER_VIRTUAL_PC, USER_STACK_TOP);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(machine.memory().load_i32(PARENT_PROOF).unwrap(), 1);
    assert_eq!(
        machine.k16_cpu_mut(boot_cpu).unwrap().address_mode(),
        K16AddressMode::Physical
    );
}

#[test]
fn computer_machine_mmu0_can_override_trap_return_to_translated_parent_context() {
    const RAM_SIZE: usize = 0x5000;
    const SETUP_PC: u32 = 0x0100;
    const HANDLER_PC: u32 = 0x0200;
    const CHILD_PHYSICAL_PC: u32 = 0x1000;
    const CHILD_VIRTUAL_PC: u32 = 0x5000;
    const PARENT_PHYSICAL_PC: u32 = 0x3000;
    const PARENT_VIRTUAL_PC: u32 = 0x7000;
    const PARENT_PROOF_PHYSICAL: u32 = 0x2000;
    const PARENT_PROOF_VIRTUAL: u32 = 0x8000;
    const KERNEL_STACK_TOP: u32 = 0x4800;
    const PARENT_KERNEL_STACK_TOP: u32 = 0x4400;
    const CHILD_STACK_TOP: u32 = 0x9000;

    let mut machine = ComputerMachine::new(RAM_SIZE).unwrap();
    let setup = k16_words(&[
        k16_const32(1, HANDLER_PC)[0],
        k16_const32(1, HANDLER_PC)[1],
        k16_const32(1, HANDLER_PC)[2],
        k16_write_csr(K16_CSR_TRAP_VECTOR, 1),
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[0],
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[1],
        k16_const32(K16_STACK_POINTER_REGISTER, KERNEL_STACK_TOP)[2],
        k16_wait(),
    ]);
    let handler = k16_words(&[
        k16_const32(1, PARENT_VIRTUAL_PC)[0],
        k16_const32(1, PARENT_VIRTUAL_PC)[1],
        k16_const32(1, PARENT_VIRTUAL_PC)[2],
        k16_write_csr(K16_CSR_TRAP_RESUME_PC, 1),
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[0],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[1],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[2],
        k16_const4(2, 1),
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[0],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[1],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[2],
        k16_const32(2, PARENT_KERNEL_STACK_TOP)[0],
        k16_const32(2, PARENT_KERNEL_STACK_TOP)[1],
        k16_const32(2, PARENT_KERNEL_STACK_TOP)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[0],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[1],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[2],
        k16_const4(
            2,
            ComputerMachine::MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE as u8,
        ),
        k16_store32(1, 2),
        k16_iret(),
    ]);
    let child = k16_words(&[k16_syscall(1), k16_halt()]);
    let parent = k16_words(&[
        k16_const32(1, PARENT_PROOF_VIRTUAL)[0],
        k16_const32(1, PARENT_PROOF_VIRTUAL)[1],
        k16_const32(1, PARENT_PROOF_VIRTUAL)[2],
        k16_const4(2, 1),
        k16_store32(1, 2),
        k16_halt(),
    ]);
    machine.write_guest_ram_bytes(SETUP_PC, &setup).unwrap();
    machine.write_guest_ram_bytes(HANDLER_PC, &handler).unwrap();
    machine
        .write_guest_ram_bytes(CHILD_PHYSICAL_PC, &child)
        .unwrap();
    machine
        .write_guest_ram_bytes(PARENT_PHYSICAL_PC, &parent)
        .unwrap();
    let parent_address_space = machine.create_mmu_address_space().unwrap();
    assert_eq!(parent_address_space.raw(), 1);
    machine
        .map_mmu_pages(
            parent_address_space,
            PARENT_VIRTUAL_PC,
            PARENT_PHYSICAL_PC,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();
    machine
        .map_mmu_pages(
            parent_address_space,
            PARENT_PROOF_VIRTUAL,
            PARENT_PROOF_PHYSICAL,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();
    let child_address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            child_address_space,
            CHILD_VIRTUAL_PC,
            CHILD_PHYSICAL_PC,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(SETUP_PC, 64);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Wait,
    );
    machine
        .k16_cpu_mut(boot_cpu)
        .unwrap()
        .enter_user_address_space(child_address_space, CHILD_VIRTUAL_PC, CHILD_STACK_TOP);

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(machine.memory().load_i32(PARENT_PROOF_PHYSICAL).unwrap(), 1);
    assert_eq!(
        machine.k16_cpu_mut(boot_cpu).unwrap().address_mode(),
        K16AddressMode::Translated {
            address_space: parent_address_space
        }
    );
    assert_eq!(
        machine
            .k16_cpu_mut(boot_cpu)
            .unwrap()
            .trap_kernel_stack_pointer(),
        PARENT_KERNEL_STACK_TOP
    );
}

#[test]
fn computer_machine_mmu0_activation_uses_command_kernel_stack_for_user_traps() {
    const RAM_SIZE: usize = 0x6000;
    const KERNEL_PC: u32 = 0x0100;
    const HANDLER_PC: u32 = 0x0200;
    const USER_PHYSICAL_PC: u32 = 0x1000;
    const USER_VIRTUAL_PC: u32 = 0x4000;
    const PROOF_ADDR: u32 = 0x3000;
    const PARENT_STACK_TOP: u32 = 0x2800;
    const KERNEL_STACK_TOP: u32 = 0x3800;
    const BOOT_STACK_TOP: u32 = 0x4800;
    const USER_STACK_TOP: u32 = 0x9000;

    let bios = k16_words(&[k16_halt()]);
    let (mut machine, _) =
        ComputerMachine::from_k16_bios_flash(&bios, RAM_SIZE, 8).expect("machine creates");
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_PC,
            USER_PHYSICAL_PC,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();

    let kernel = k16_words(&[
        k16_const32(1, HANDLER_PC)[0],
        k16_const32(1, HANDLER_PC)[1],
        k16_const32(1, HANDLER_PC)[2],
        k16_write_csr(K16_CSR_TRAP_VECTOR, 1),
        k16_const32(K16_STACK_POINTER_REGISTER, PARENT_STACK_TOP)[0],
        k16_const32(K16_STACK_POINTER_REGISTER, PARENT_STACK_TOP)[1],
        k16_const32(K16_STACK_POINTER_REGISTER, PARENT_STACK_TOP)[2],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[0],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[1],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[2],
        k16_const32(2, address_space.raw())[0],
        k16_const32(2, address_space.raw())[1],
        k16_const32(2, address_space.raw())[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[0],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[1],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[2],
        k16_const32(2, KERNEL_STACK_TOP)[0],
        k16_const32(2, KERNEL_STACK_TOP)[1],
        k16_const32(2, KERNEL_STACK_TOP)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[0],
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[1],
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[2],
        k16_const32(2, USER_VIRTUAL_PC)[0],
        k16_const32(2, USER_VIRTUAL_PC)[1],
        k16_const32(2, USER_VIRTUAL_PC)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[0],
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[1],
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[2],
        k16_const32(2, USER_STACK_TOP)[0],
        k16_const32(2, USER_STACK_TOP)[1],
        k16_const32(2, USER_STACK_TOP)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[0],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[1],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[2],
        k16_const4(
            2,
            ComputerMachine::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE as u8,
        ),
        k16_store32(1, 2),
        k16_halt(),
    ]);
    let handler = k16_words(&[
        k16_const32(1, PROOF_ADDR)[0],
        k16_const32(1, PROOF_ADDR)[1],
        k16_const32(1, PROOF_ADDR)[2],
        k16_store32(1, K16_STACK_POINTER_REGISTER),
        k16_halt(),
    ]);
    let user = k16_words(&[k16_syscall(0), k16_halt()]);
    machine.write_guest_ram_bytes(KERNEL_PC, &kernel).unwrap();
    machine.write_guest_ram_bytes(HANDLER_PC, &handler).unwrap();
    machine
        .write_guest_ram_bytes(USER_PHYSICAL_PC, &user)
        .unwrap();
    let boot_cpu = machine
        .boot_handoff_k16_from_ram_with_stack(KERNEL_PC, kernel.len() as u32, 128, BOOT_STACK_TOP)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine.memory().load_i32(PROOF_ADDR).unwrap(),
        KERNEL_STACK_TOP as i32
    );
    assert_ne!(
        machine.memory().load_i32(PROOF_ADDR).unwrap(),
        BOOT_STACK_TOP as i32
    );
    assert_ne!(
        machine.memory().load_i32(PROOF_ADDR).unwrap(),
        PARENT_STACK_TOP as i32
    );
}

#[test]
fn computer_machine_mmu0_activation_enters_user_with_restored_trap_frame_registers() {
    const RAM_SIZE: usize = 0x6000;
    const KERNEL_PC: u32 = 0x0100;
    const USER_PHYSICAL_PC: u32 = 0x1000;
    const USER_DATA_PHYSICAL: u32 = 0x2000;
    const USER_VIRTUAL_PC: u32 = 0x4000;
    const USER_VIRTUAL_DATA: u32 = 0x8000;
    const KERNEL_STACK_TOP: u32 = 0x3800;
    const USER_STACK_TOP: u32 = 0x9000;

    let bios = k16_words(&[k16_halt()]);
    let (mut machine, _) =
        ComputerMachine::from_k16_bios_flash(&bios, RAM_SIZE, 8).expect("machine creates");
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_PC,
            USER_PHYSICAL_PC,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();
    machine
        .map_mmu_pages(
            address_space,
            USER_VIRTUAL_DATA,
            USER_DATA_PHYSICAL,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();

    let kernel = k16_words(&[
        k16_const4(1, 1),
        k16_write_csr(K16_CSR_TRAP_FRAME_INDEX, 1),
        k16_const32(2, 0x1111)[0],
        k16_const32(2, 0x1111)[1],
        k16_const32(2, 0x1111)[2],
        k16_write_csr(K16_CSR_TRAP_FRAME_REGISTER, 2),
        k16_const4(1, 2),
        k16_write_csr(K16_CSR_TRAP_FRAME_INDEX, 1),
        k16_const32(2, 0x2222)[0],
        k16_const32(2, 0x2222)[1],
        k16_const32(2, 0x2222)[2],
        k16_write_csr(K16_CSR_TRAP_FRAME_REGISTER, 2),
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[0],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[1],
        k16_const32(1, ComputerMachine::MMU0_ADDRESS_SPACE)[2],
        k16_const32(2, address_space.raw())[0],
        k16_const32(2, address_space.raw())[1],
        k16_const32(2, address_space.raw())[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[0],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[1],
        k16_const32(1, ComputerMachine::MMU0_PHYSICAL_START)[2],
        k16_const32(2, KERNEL_STACK_TOP)[0],
        k16_const32(2, KERNEL_STACK_TOP)[1],
        k16_const32(2, KERNEL_STACK_TOP)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[0],
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[1],
        k16_const32(1, ComputerMachine::MMU0_ENTRY_PC)[2],
        k16_const32(2, USER_VIRTUAL_PC)[0],
        k16_const32(2, USER_VIRTUAL_PC)[1],
        k16_const32(2, USER_VIRTUAL_PC)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[0],
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[1],
        k16_const32(1, ComputerMachine::MMU0_STACK_POINTER)[2],
        k16_const32(2, USER_STACK_TOP)[0],
        k16_const32(2, USER_STACK_TOP)[1],
        k16_const32(2, USER_STACK_TOP)[2],
        k16_store32(1, 2),
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[0],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[1],
        k16_const32(1, ComputerMachine::MMU0_COMMAND)[2],
        k16_const4(
            2,
            ComputerMachine::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE as u8,
        ),
        k16_store32(1, 2),
        k16_halt(),
    ]);
    let user = k16_words(&[
        k16_const32(3, USER_VIRTUAL_DATA)[0],
        k16_const32(3, USER_VIRTUAL_DATA)[1],
        k16_const32(3, USER_VIRTUAL_DATA)[2],
        k16_store32(3, 1),
        k16_const32(3, USER_VIRTUAL_DATA + 4)[0],
        k16_const32(3, USER_VIRTUAL_DATA + 4)[1],
        k16_const32(3, USER_VIRTUAL_DATA + 4)[2],
        k16_store32(3, 2),
        k16_halt(),
    ]);
    machine.write_guest_ram_bytes(KERNEL_PC, &kernel).unwrap();
    machine
        .write_guest_ram_bytes(USER_PHYSICAL_PC, &user)
        .unwrap();
    let boot_cpu = machine
        .boot_handoff_k16_from_ram_with_stack(KERNEL_PC, kernel.len() as u32, 128, KERNEL_STACK_TOP)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine.memory().load_i32(USER_DATA_PHYSICAL).unwrap(),
        0x1111
    );
    assert_eq!(
        machine.memory().load_i32(USER_DATA_PHYSICAL + 4).unwrap(),
        0x2222
    );
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
fn computer_machine_mmu_address_space_destroy_removes_mappings() {
    let mut machine = ComputerMachine::new(0x4000).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            0x4000,
            0x1000,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();

    assert!(machine.destroy_mmu_address_space(address_space));

    assert!(machine
        .map_mmu_pages(
            address_space,
            0x8000,
            0x2000,
            1,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .is_err());
    assert!(!machine.destroy_mmu_address_space(address_space));
}

#[test]
fn computer_machine_mmu0_destroy_address_space_command_removes_mappings() {
    const KERNEL_PC: u32 = 0x0000;

    let mut machine = ComputerMachine::new(0x4000).unwrap();
    let address_space = machine.create_mmu_address_space().unwrap();
    machine
        .map_mmu_pages(
            address_space,
            0x4000,
            0x1000,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();
    machine
        .write_guest_ram_bytes(KERNEL_PC, &k16_words(&[k16_nop(), k16_halt()]))
        .unwrap();
    let boot_cpu = machine.install_k16_boot_cpu_for_tests(KERNEL_PC, 16);

    submit_mmu0_command(
        &mut machine,
        address_space.raw(),
        0,
        0,
        0,
        ComputerMachine::MMU0_COMMAND_DESTROY_ADDRESS_SPACE,
    );

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt,
    );
    assert_eq!(
        machine.bus_load_i32(ComputerMachine::MMU0_STATUS).unwrap(),
        ComputerMachine::MMU0_STATUS_DONE,
    );
    assert!(machine
        .map_mmu_pages(
            address_space,
            0x8000,
            0x2000,
            1,
            MmuMapFlags::USER_ACCESSIBLE,
        )
        .is_err());
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
fn computer_gpu0_fills_and_copies_rectangles() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine
        .bus
        .store_i32(ComputerMachine::GPU0_COLOR, 0x07e0)
        .unwrap();
    machine.bus.store_i32(ComputerMachine::GPU0_X, 2).unwrap();
    machine.bus.store_i32(ComputerMachine::GPU0_Y, 3).unwrap();
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
        .store_i32(
            ComputerMachine::GPU0_COMMAND,
            ComputerMachine::GPU0_COMMAND_FILL_RECT,
        )
        .unwrap();
    machine
        .bus
        .store_i32(
            ComputerMachine::GPU0_COMMAND,
            ComputerMachine::GPU0_COMMAND_PRESENT,
        )
        .unwrap();
    machine.drain_gpu0_frames();

    machine
        .bus
        .store_i32(ComputerMachine::GPU0_SRC_X, 2)
        .unwrap();
    machine
        .bus
        .store_i32(ComputerMachine::GPU0_SRC_Y, 3)
        .unwrap();
    machine.bus.store_i32(ComputerMachine::GPU0_X, 6).unwrap();
    machine.bus.store_i32(ComputerMachine::GPU0_Y, 1).unwrap();
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
        .store_i32(
            ComputerMachine::GPU0_COMMAND,
            ComputerMachine::GPU0_COMMAND_COPY_RECT,
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
    assert!(frames[0].tiles.is_empty());
    assert_eq!(
        frames[0].operations,
        vec![DisplayFrameOperation::CopyRect {
            src_x: 2,
            src_y: 3,
            width: 2,
            height: 2,
            dst_x: 6,
            dst_y: 1,
        }],
    );
}

#[test]
fn computer_gpu0_stats_snapshot_counts_blit_and_present_traffic() {
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

    let snapshot = machine.stats_snapshot();
    let gpu = snapshot
        .devices
        .iter()
        .find(|device| device.name == "gpu0")
        .unwrap();

    assert_eq!(gpu.gpu.blit_buffer_commands, 1);
    assert_eq!(gpu.gpu.blit_pixels, 4);
    assert_eq!(gpu.gpu.blit_source_bytes, 8);
    assert_eq!(gpu.gpu.present_commands, 1);
    assert_eq!(gpu.gpu.frames, 1);
    assert_eq!(gpu.gpu.frame_tiles, 1);
    assert_eq!(gpu.gpu.frame_payload_bytes, 512);
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
fn storage0_stats_snapshot_counts_block_commands_flush_and_failures() {
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            vec![0; 1024],
            false,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine.memory_mut().store_u8(512, 0xA5).unwrap();
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
    machine.memory_mut().store_u8(512, 0x5A).unwrap();
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
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 3)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    let snapshot = machine.stats_snapshot();
    let storage0 = snapshot
        .devices
        .iter()
        .find(|device| device.name == "storage0")
        .expect("storage0 stats are present");
    assert_eq!(storage0.storage.read_commands, 1);
    assert_eq!(storage0.storage.write_commands, 1);
    assert_eq!(storage0.storage.flush_commands, 1);
    assert_eq!(storage0.storage.bytes_read, 512);
    assert_eq!(storage0.storage.bytes_written, 512);
    assert_eq!(storage0.storage.failed_commands, 1);
    assert_eq!(storage0.storage.unique_read_blocks, 1);
    assert_eq!(storage0.storage.repeated_read_blocks, 0);
}

#[test]
fn storage0_stats_snapshot_counts_unique_and_repeated_backend_read_lbas() {
    let profile = ComputerMachineProfile::new(2048).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            vec![0; 33 * 512],
            false,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();

    for lba in 0..=32 {
        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, lba)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();
    }
    machine
        .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    let snapshot = machine.stats_snapshot();
    let storage0 = snapshot
        .devices
        .iter()
        .find(|device| device.name == "storage0")
        .expect("storage0 stats are present");
    assert_eq!(storage0.storage.read_commands, 34);
    assert_eq!(storage0.storage.bytes_read, 34 * 512);
    assert_eq!(storage0.storage.unique_read_blocks, 33);
    assert_eq!(storage0.storage.repeated_read_blocks, 1);
}

#[test]
fn storage0_stats_snapshot_classifies_partition_and_k16fs_backend_read_ownership() {
    let mut media = vec![0_u8; 8 * 512];
    write_k16pt_test_table(&mut media, 1, 3, 4, 4);
    write_k16fs_test_superblock(&mut media[512..1024], 3, 1, 1, 2, 1);
    write_k16fs_test_superblock(&mut media[2048..2560], 4, 1, 1, 2, 1);

    let profile = ComputerMachineProfile::new(8192).with_hardware(
        ComputerHardwareConfig::storage_port_with_media(
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            media,
            false,
        ),
    );
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 8)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 0)
        .unwrap();
    machine
        .bus_store_i32(
            computer_abi::STORAGE0_COMMAND,
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();

    let snapshot = machine.stats_snapshot();
    let storage0 = snapshot
        .devices
        .iter()
        .find(|device| device.name == "storage0")
        .expect("storage0 stats are present");
    assert_eq!(storage0.storage.partition_table_read_blocks, 1);
    assert_eq!(storage0.storage.boot_metadata_read_blocks, 3);
    assert_eq!(storage0.storage.boot_data_read_blocks, 0);
    assert_eq!(storage0.storage.root_metadata_read_blocks, 3);
    assert_eq!(storage0.storage.root_data_read_blocks, 1);
    assert_eq!(storage0.storage.unknown_read_blocks, 0);
}

#[test]
fn stats_snapshot_reads_registered_os_stats_from_guest_ram() {
    let profile = ComputerMachineProfile::new(2048).with_hardware(ComputerHardwareConfig::control(
        computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
        computer_abi::CONTROL_BASE,
    ));
    let mut machine = ComputerMachine::from_profile(profile).unwrap();
    machine.memory_mut().store_u64(512, 11).unwrap();
    machine.memory_mut().store_u64(520, 12).unwrap();
    machine.memory_mut().store_u64(528, 13).unwrap();
    machine.memory_mut().store_u64(536, 14).unwrap();
    machine.memory_mut().store_u64(544, 15).unwrap();
    machine.memory_mut().store_u64(552, 16).unwrap();
    machine.memory_mut().store_u64(560, 17).unwrap();
    machine.memory_mut().store_u64(568, 18).unwrap();
    machine.memory_mut().store_u64(576, 19).unwrap();
    machine.memory_mut().store_u64(584, 20).unwrap();
    machine.memory_mut().store_u64(592, 21).unwrap();
    machine.memory_mut().store_u64(600, 22).unwrap();
    machine.memory_mut().store_u64(608, 23).unwrap();
    machine.memory_mut().store_u64(616, 24).unwrap();
    machine.memory_mut().store_u64(624, 25).unwrap();
    machine.memory_mut().store_u64(632, 26).unwrap();
    machine.memory_mut().store_u64(640, 27).unwrap();
    machine.memory_mut().store_u64(648, 28).unwrap();
    machine.memory_mut().store_u64(656, 29).unwrap();
    machine.memory_mut().store_u64(664, 30).unwrap();
    machine.memory_mut().store_u64(672, 31).unwrap();
    machine.memory_mut().store_u64(680, 32).unwrap();
    machine.memory_mut().store_u64(688, 33).unwrap();
    machine.memory_mut().store_u64(696, 34).unwrap();
    machine
        .bus_store_i32(computer_abi::CONTROL_OS_STATS_ADDR, 512)
        .unwrap();
    machine
        .bus_store_i32(computer_abi::CONTROL_OS_STATS_SIZE, 192)
        .unwrap();

    let snapshot = machine.stats_snapshot();

    assert_eq!(snapshot.os.path_lookups, 11);
    assert_eq!(snapshot.os.inode_loads, 12);
    assert_eq!(snapshot.os.dir_entry_scans, 13);
    assert_eq!(snapshot.os.file_opens, 14);
    assert_eq!(snapshot.os.file_reads, 15);
    assert_eq!(snapshot.os.stat_calls, 16);
    assert_eq!(snapshot.os.process_spawns, 17);
    assert_eq!(snapshot.os.program_loads, 18);
    assert_eq!(snapshot.os.dynamic_import_loads, 19);
    assert_eq!(snapshot.os.library_loads, 20);
    assert_eq!(snapshot.os.read_dir_calls, 21);
    assert_eq!(snapshot.os.program_load_bytes, 22);
    assert_eq!(snapshot.os.dynamic_import_bytes, 23);
    assert_eq!(snapshot.os.library_load_bytes, 24);
    assert_eq!(snapshot.os.generic_file_data_read_blocks, 25);
    assert_eq!(snapshot.os.generic_file_data_read_bytes, 26);
    assert_eq!(snapshot.os.read_dir_data_read_blocks, 27);
    assert_eq!(snapshot.os.read_dir_data_read_bytes, 28);
    assert_eq!(snapshot.os.program_data_read_blocks, 29);
    assert_eq!(snapshot.os.program_data_read_bytes, 30);
    assert_eq!(snapshot.os.dynamic_import_data_read_blocks, 31);
    assert_eq!(snapshot.os.dynamic_import_data_read_bytes, 32);
    assert_eq!(snapshot.os.library_data_read_blocks, 33);
    assert_eq!(snapshot.os.library_data_read_bytes, 34);
}

fn write_k16pt_test_table(
    media: &mut [u8],
    boot_start_lba: u32,
    boot_blocks: u32,
    root_start_lba: u32,
    root_blocks: u32,
) {
    media[0..5].copy_from_slice(b"K16PT");
    media[5] = 1;
    media[6] = 2;
    write_u32_test(media, 8, 0);
    write_u32_test(media, 12, 1);
    write_k16pt_test_entry(media, 16, b"BOOT", boot_start_lba, boot_blocks, b"boot");
    write_k16pt_test_entry(media, 48, b"ROOT", root_start_lba, root_blocks, b"root");
}

fn write_k16pt_test_entry(
    media: &mut [u8],
    offset: usize,
    tag: &[u8; 4],
    start_lba: u32,
    blocks: u32,
    name: &[u8],
) {
    media[offset..offset + 4].copy_from_slice(tag);
    write_u32_test(media, offset + 8, start_lba);
    write_u32_test(media, offset + 12, blocks);
    media[offset + 16..offset + 16 + name.len()].copy_from_slice(name);
}

fn write_k16fs_test_superblock(
    block: &mut [u8],
    total_blocks: u32,
    bitmap_start_block: u32,
    bitmap_block_count: u32,
    inode_table_start_block: u32,
    inode_table_block_count: u32,
) {
    block[0..5].copy_from_slice(b"K16FS");
    block[5] = 1;
    write_u32_test(block, 0x08, 512);
    write_u32_test(block, 0x0c, total_blocks);
    write_u32_test(block, 0x10, bitmap_start_block);
    write_u32_test(block, 0x14, bitmap_block_count);
    write_u32_test(block, 0x18, inode_table_start_block);
    write_u32_test(block, 0x1c, inode_table_block_count);
    write_u32_test(block, 0x20, 1);
}

fn write_u32_test(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
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
    assert_eq!(
        ComputerMachine::CONTROL_OS_STATS_ADDR,
        computer_abi::CONTROL_OS_STATS_ADDR,
    );
    assert_eq!(
        ComputerMachine::CONTROL_OS_STATS_SIZE,
        computer_abi::CONTROL_OS_STATS_SIZE,
    );
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
    assert_eq!(ComputerMachine::GPU0_SRC_X, computer_abi::GPU0_SRC_X,);
    assert_eq!(ComputerMachine::GPU0_SRC_Y, computer_abi::GPU0_SRC_Y,);
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_BLIT_BUFFER,
        computer_abi::GPU0_COMMAND_BLIT_BUFFER,
    );
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_PRESENT,
        computer_abi::GPU0_COMMAND_PRESENT,
    );
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_FILL_RECT,
        computer_abi::GPU0_COMMAND_FILL_RECT,
    );
    assert_eq!(
        ComputerMachine::GPU0_COMMAND_COPY_RECT,
        computer_abi::GPU0_COMMAND_COPY_RECT,
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
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE,
        computer_abi::MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE,
    );
    assert_eq!(
        ComputerMachine::MMU0_COMMAND_DESTROY_ADDRESS_SPACE,
        computer_abi::MMU0_COMMAND_DESTROY_ADDRESS_SPACE,
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

fn k16_branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

fn append_k16_store_const32(words: &mut Vec<u16>, address: u32, value: u32) {
    words.extend(k16_const32(4, address));
    words.extend(k16_const32(5, value));
    words.push(k16_store32(4, 5));
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!((-8..=7).contains(&value));
    u16::from((value as i16 as u16 & 0x000f) as u8)
}

fn k16_write_csr(csr: u32, src: u8) -> u16 {
    0x0003 | ((csr as u16) << 8) | (u16::from(src) << 4)
}

fn k16_syscall(register: u8) -> u16 {
    0x0005 | (u16::from(register) << 8)
}

fn k16_iret() -> u16 {
    0x0004
}

fn k16_wait() -> u16 {
    0x0006
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
