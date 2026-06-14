use super::{checked_ram_range, BootHandoffError, ComputerCpuContext, ComputerMachine, CpuId};
use crate::computer::devices::BiosFlashDevice;
use crate::computer::profile::ComputerMachineProfile;
use crate::k16::{K16Cpu, K16Signal};

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
    let signal = {
        let cpu = machine
            .cpus
            .get_mut(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        match cpu {
            ComputerCpuContext::K16 { cpu, max_steps } => cpu
                .run_until_signal_with_mmu(&mut machine.bus, &machine.address_spaces, *max_steps)
                .map_err(|error| error.to_string()),
        }
    };
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
    signal
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
