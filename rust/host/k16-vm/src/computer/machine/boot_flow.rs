use super::{checked_ram_range, BootHandoffError, ComputerCpuContext, ComputerMachine, CpuId};
use crate::computer::devices::BiosFlashDevice;
use crate::computer::devices::MmuControlCommand;
use crate::computer::profile::ComputerMachineProfile;
use crate::computer_abi;
use crate::k16::{K16AddressMode, K16Cpu, K16PrivilegeMode, K16Signal};
use crate::mmu::{MmuAccess, MmuAddressSpace, MmuAddressSpaceId, MmuMapFlags, MmuPrivilege};

pub(super) fn from_k16_bios_flash(
    bios_flash: &[u8],
    memory_size: usize,
    max_steps: u64,
) -> Result<(ComputerMachine, CpuId), String> {
    from_k16_bios_flash_with_profile(
        bios_flash,
        ComputerMachineProfile::computer_v1(memory_size),
        max_steps,
    )
}

pub(super) fn from_k16_bios_flash_with_profile(
    bios_flash: &[u8],
    profile: ComputerMachineProfile,
    max_steps: u64,
) -> Result<(ComputerMachine, CpuId), String> {
    validate_bios_flash(bios_flash)?;

    let mut machine = ComputerMachine::from_profile(profile).map_err(|error| error.to_string())?;
    map_k16_bios_flash(&mut machine, bios_flash.to_vec())?;
    let boot_cpu = spawn_k16_boot_cpu(
        &mut machine,
        ComputerMachine::K16_BIOS_FLASH_BASE,
        max_steps,
    )?;
    Ok((machine, boot_cpu))
}

pub(super) fn boot_handoff_k16_from_ram(
    machine: &mut ComputerMachine,
    entry_pc: u32,
    byte_len: u32,
    max_steps: u64,
) -> Result<CpuId, BootHandoffError> {
    boot_handoff_k16_from_ram_inner(machine, entry_pc, byte_len, max_steps, None)
}

pub(super) fn boot_handoff_k16_from_ram_with_stack(
    machine: &mut ComputerMachine,
    entry_pc: u32,
    byte_len: u32,
    max_steps: u64,
    stack_top: u32,
) -> Result<CpuId, BootHandoffError> {
    boot_handoff_k16_from_ram_inner(machine, entry_pc, byte_len, max_steps, Some(stack_top))
}

fn boot_handoff_k16_from_ram_inner(
    machine: &mut ComputerMachine,
    entry_pc: u32,
    byte_len: u32,
    max_steps: u64,
    stack_top: Option<u32>,
) -> Result<CpuId, BootHandoffError> {
    let boot_cpu = machine.boot_cpu.ok_or(BootHandoffError::MissingBootCpu)?;
    if byte_len == 0 {
        return Err(BootHandoffError::EmptyImage);
    }
    checked_ram_range(entry_pc, byte_len, machine.bus.memory().len())?;
    if let Some(stack_top) = stack_top {
        validate_stack_top(stack_top, machine.bus.memory().len())?;
    }
    machine.cpus[boot_cpu] = ComputerCpuContext::K16 {
        cpu: match stack_top {
            Some(stack_top) => K16Cpu::new_with_stack(entry_pc, stack_top),
            None => K16Cpu::new(entry_pc),
        },
        max_steps: max_steps.max(1),
    };
    Ok(boot_cpu)
}

fn validate_stack_top(stack_top: u32, ram_len: usize) -> Result<(), BootHandoffError> {
    if stack_top % 4 != 0 {
        return Err(BootHandoffError::StackTopMisaligned { stack_top });
    }
    let stack_top = usize::try_from(stack_top)
        .map_err(|_| BootHandoffError::StackTopOutOfBounds { stack_top, ram_len })?;
    if stack_top == 0 || stack_top > ram_len {
        return Err(BootHandoffError::StackTopOutOfBounds {
            stack_top: stack_top as u32,
            ram_len,
        });
    }
    Ok(())
}

pub(super) fn run_boot_k16_until_signal(
    machine: &mut ComputerMachine,
    cpu_id: CpuId,
) -> Result<K16Signal, String> {
    if machine.boot_cpu != Some(cpu_id) {
        return Err(format!("CPU {cpu_id} is not the boot CPU"));
    }
    // ComputerMachine owns the full-computer reaction to CPU results. The CPU
    // executes instructions; the machine translates halt/fault outcomes into
    // control-device state visible to the host.
    loop {
        let signal = {
            let cpu = machine
                .cpus
                .get_mut(cpu_id)
                .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
            match cpu {
                ComputerCpuContext::K16 { cpu, max_steps } => cpu
                    .run_until_signal_with_mmu(
                        &mut machine.bus,
                        &machine.address_spaces,
                        *max_steps,
                    )
                    .map_err(|error| error.to_string()),
            }
        };
        if matches!(signal, Ok(K16Signal::Yield)) && apply_pending_mmu0_command(machine, cpu_id)? {
            continue;
        }
        match &signal {
            Ok(K16Signal::Halt) => {
                set_halted_exit_code(machine, 0)?;
            }
            Ok(K16Signal::Wait) => {}
            Ok(K16Signal::Yield) => {}
            Ok(K16Signal::StepLimitExceeded) => {}
            Err(message) => {
                set_panic_from_fault(machine, message)?;
            }
        }
        return signal;
    }
}

fn apply_pending_mmu0_command(
    machine: &mut ComputerMachine,
    cpu_id: CpuId,
) -> Result<bool, String> {
    let Some(command) = machine.take_pending_mmu0_command() else {
        return Ok(false);
    };
    let result = match apply_mmu0_command(machine, cpu_id, command) {
        Ok(result) => {
            machine.finish_mmu0_success(result);
            Ok(())
        }
        Err(error) => {
            machine.finish_mmu0_error(error);
            Ok(())
        }
    };
    result.map(|()| true)
}

fn apply_mmu0_command(
    machine: &mut ComputerMachine,
    cpu_id: CpuId,
    command: MmuControlCommand,
) -> Result<u32, i32> {
    match command.command {
        computer_abi::MMU0_COMMAND_CREATE_ADDRESS_SPACE => machine
            .create_mmu_address_space()
            .map(MmuAddressSpaceId::raw)
            .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT),
        computer_abi::MMU0_COMMAND_MAP_PAGES => {
            let flags = mmu0_flags(command.flags)?;
            machine
                .map_mmu_pages(
                    MmuAddressSpaceId::from_raw(command.address_space),
                    command.virtual_start,
                    command.physical_start,
                    command.page_count,
                    flags,
                )
                .map(|()| 0)
                .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT)
        }
        computer_abi::MMU0_COMMAND_PROTECT_PAGES => {
            let flags = mmu0_flags(command.flags)?;
            machine
                .protect_mmu_pages(
                    MmuAddressSpaceId::from_raw(command.address_space),
                    command.virtual_start,
                    command.page_count,
                    flags,
                )
                .map(|()| 0)
                .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT)
        }
        computer_abi::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE => {
            let address_space = MmuAddressSpaceId::from_raw(command.address_space);
            if machine.address_spaces.get(address_space).is_none() {
                return Err(computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE);
            }
            let cpu = machine
                .k16_cpu_mut(cpu_id)
                .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT)?;
            let kernel_stack_pointer = match command.physical_start {
                0 => match cpu.trap_kernel_stack_pointer() {
                    0 => cpu.register(usize::from(crate::k16::K16_STACK_POINTER_REGISTER)),
                    stack_pointer => stack_pointer,
                },
                stack_pointer => stack_pointer,
            };
            cpu.enter_user_address_space_with_kernel_stack(
                address_space,
                command.entry_pc,
                command.stack_pointer,
                kernel_stack_pointer,
            );
            Ok(0)
        }
        computer_abi::MMU0_COMMAND_COPY_FROM_USER => copy_from_user(machine, command),
        computer_abi::MMU0_COMMAND_COPY_TO_USER => copy_to_user(machine, command),
        computer_abi::MMU0_COMMAND_SET_TRAP_RETURN_PHYSICAL => {
            machine
                .k16_cpu_mut(cpu_id)
                .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT)?
                .set_trap_return_mode(K16AddressMode::Physical, K16PrivilegeMode::Kernel);
            Ok(0)
        }
        computer_abi::MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE => {
            if command.physical_start == 0 {
                return Err(computer_abi::MMU0_ERROR_INVALID_ARGUMENT);
            }
            let address_space = MmuAddressSpaceId::from_raw(command.address_space);
            if machine.address_spaces.get(address_space).is_none() {
                return Err(computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE);
            }
            let cpu = machine
                .k16_cpu_mut(cpu_id)
                .map_err(|_| computer_abi::MMU0_ERROR_INVALID_ARGUMENT)?;
            cpu.set_trap_kernel_stack_pointer(command.physical_start);
            cpu.set_trap_return_mode(
                K16AddressMode::Translated { address_space },
                K16PrivilegeMode::User,
            );
            Ok(0)
        }
        computer_abi::MMU0_COMMAND_NOP => Ok(0),
        _ => Err(computer_abi::MMU0_ERROR_INVALID_COMMAND),
    }
}

fn copy_from_user(machine: &mut ComputerMachine, command: MmuControlCommand) -> Result<u32, i32> {
    let address_space = machine
        .address_spaces
        .get(MmuAddressSpaceId::from_raw(command.address_space))
        .ok_or(computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE)?;
    validate_mmu0_physical_range(machine, command.physical_start, command.page_count)?;
    let bytes = read_user_bytes(
        address_space,
        machine,
        command.virtual_start,
        command.page_count,
    )?;
    for (offset, byte) in bytes.iter().copied().enumerate() {
        machine
            .bus
            .memory_mut()
            .store_u8(command.physical_start + offset as u32, byte)
            .map_err(|_| computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS)?;
    }
    Ok(command.page_count)
}

fn copy_to_user(machine: &mut ComputerMachine, command: MmuControlCommand) -> Result<u32, i32> {
    validate_mmu0_physical_range(machine, command.physical_start, command.page_count)?;
    let bytes = read_physical_bytes(machine, command.physical_start, command.page_count)?;
    let address_space = machine
        .address_spaces
        .get(MmuAddressSpaceId::from_raw(command.address_space))
        .ok_or(computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE)?;
    let physical_destinations = translate_user_range(
        address_space,
        command.virtual_start,
        command.page_count,
        MmuAccess::Store,
    )?;
    for (physical, byte) in physical_destinations.into_iter().zip(bytes) {
        machine
            .bus
            .memory_mut()
            .store_u8(physical, byte)
            .map_err(|_| computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS)?;
    }
    Ok(command.page_count)
}

fn read_user_bytes(
    address_space: &MmuAddressSpace,
    machine: &ComputerMachine,
    virtual_start: u32,
    byte_count: u32,
) -> Result<Vec<u8>, i32> {
    translate_user_range(address_space, virtual_start, byte_count, MmuAccess::Load)?
        .into_iter()
        .map(|physical| {
            machine
                .bus
                .memory()
                .load_u8(physical)
                .map_err(|_| computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS)
        })
        .collect()
}

fn read_physical_bytes(
    machine: &ComputerMachine,
    physical_start: u32,
    byte_count: u32,
) -> Result<Vec<u8>, i32> {
    validate_mmu0_physical_range(machine, physical_start, byte_count)?;
    (0..byte_count)
        .map(|offset| {
            machine
                .bus
                .memory()
                .load_u8(physical_start + offset)
                .map_err(|_| computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS)
        })
        .collect()
}

fn translate_user_range(
    address_space: &MmuAddressSpace,
    virtual_start: u32,
    byte_count: u32,
    access: MmuAccess,
) -> Result<Vec<u32>, i32> {
    let capacity =
        usize::try_from(byte_count).map_err(|_| computer_abi::MMU0_ERROR_BYTE_COUNT_OVERFLOW)?;
    let mut physical = Vec::with_capacity(capacity);
    for offset in 0..byte_count {
        let virtual_address = virtual_start
            .checked_add(offset)
            .ok_or(computer_abi::MMU0_ERROR_BYTE_COUNT_OVERFLOW)?;
        physical.push(
            address_space
                .translate(virtual_address, access, MmuPrivilege::User)
                .map_err(|_| computer_abi::MMU0_ERROR_TRANSLATION_FAULT)?,
        );
    }
    Ok(physical)
}

fn validate_mmu0_physical_range(
    machine: &ComputerMachine,
    physical_start: u32,
    byte_count: u32,
) -> Result<(), i32> {
    checked_ram_range(physical_start, byte_count, machine.bus.memory().len()).map_err(|error| {
        if matches!(error, BootHandoffError::RamRangeOverflow { .. }) {
            computer_abi::MMU0_ERROR_BYTE_COUNT_OVERFLOW
        } else {
            computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS
        }
    })?;
    Ok(())
}

fn mmu0_flags(raw: u32) -> Result<MmuMapFlags, i32> {
    let known = (computer_abi::MMU0_FLAG_USER_ACCESSIBLE
        | computer_abi::MMU0_FLAG_WRITABLE
        | computer_abi::MMU0_FLAG_EXECUTABLE) as u32;
    if raw & !known != 0 {
        return Err(computer_abi::MMU0_ERROR_INVALID_ARGUMENT);
    }
    let mut flags = MmuMapFlags::NONE;
    if raw & computer_abi::MMU0_FLAG_USER_ACCESSIBLE as u32 != 0 {
        flags = flags | MmuMapFlags::USER_ACCESSIBLE;
    }
    if raw & computer_abi::MMU0_FLAG_WRITABLE as u32 != 0 {
        flags = flags | MmuMapFlags::WRITABLE;
    }
    if raw & computer_abi::MMU0_FLAG_EXECUTABLE as u32 != 0 {
        flags = flags | MmuMapFlags::EXECUTABLE;
    }
    Ok(flags)
}

pub(super) fn map_k16_bios_flash(
    machine: &mut ComputerMachine,
    bytes: Vec<u8>,
) -> Result<(), String> {
    if machine.bios_flash_device_id.is_some() {
        return Err("K16 BIOS flash is already mapped".to_string());
    }
    let device = BiosFlashDevice::new(bytes).map_err(|error| error.to_string())?;
    let device_id = machine
        .bus
        .map_mmio(ComputerMachine::K16_BIOS_FLASH_BASE, Box::new(device))
        .map_err(|error| error.to_string())?;
    machine.bios_flash_device_id = Some(device_id);
    Ok(())
}

fn validate_bios_flash(bios_flash: &[u8]) -> Result<(), String> {
    if bios_flash.is_empty() {
        return Err("K16 BIOS flash is empty".to_string());
    }
    let bios_flash_len = u32::try_from(bios_flash.len())
        .map_err(|_| "K16 BIOS flash size does not fit u32".to_string())?;
    ComputerMachine::K16_BIOS_FLASH_BASE
        .checked_add(bios_flash_len)
        .ok_or_else(|| "K16 BIOS flash range overflows address space".to_string())?;
    Ok(())
}

fn spawn_k16_boot_cpu(
    machine: &mut ComputerMachine,
    entry_pc: u32,
    max_steps: u64,
) -> Result<CpuId, String> {
    if machine.boot_cpu.is_some() {
        return Err("boot CPU is already spawned".to_string());
    }
    let cpu_id = machine.cpus.len();
    machine.cpus.push(ComputerCpuContext::K16 {
        cpu: K16Cpu::new(entry_pc),
        max_steps: max_steps.max(1),
    });
    machine.boot_cpu = Some(cpu_id);
    Ok(cpu_id)
}

fn set_halted_exit_code(machine: &mut ComputerMachine, exit_code: i32) -> Result<(), String> {
    if let Some(control) = machine.control_device_mut() {
        control.status = ComputerMachine::STATUS_HALTED;
        control.exit_code = exit_code;
    }
    Ok(())
}

fn set_panic_from_fault(machine: &mut ComputerMachine, message: &str) -> Result<(), String> {
    if let Some(control) = machine.control_device_mut() {
        control.status = ComputerMachine::STATUS_PANIC;
        control.panic_code = stable_panic_code(message);
    }
    Err(message.to_string())
}

fn stable_panic_code(message: &str) -> i32 {
    message.bytes().fold(0_i32, |hash, byte| {
        hash.wrapping_mul(31).wrapping_add(i32::from(byte))
    })
}
