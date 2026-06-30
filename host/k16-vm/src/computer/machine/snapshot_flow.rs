use super::{ComputerCpuContext, ComputerMachine};
use crate::computer::devices::{restore_regular_device_snapshot_record, snapshot_device_records};
use crate::computer::profile::ComputerMachineProfile;
use crate::computer::snapshot;
use crate::computer::snapshot::{
    ComputerCpuSnapshotRecord, ComputerDeviceSnapshotRecord, K16CpuModeSnapshotRecord,
};
use crate::k16::{K16AddressMode, K16CachedDecoder, K16Cpu, K16CpuModeSnapshot, K16PrivilegeMode};
use crate::mmu::MmuAddressSpaces;

pub(super) fn snapshot_v1(machine: &ComputerMachine) -> Result<Vec<u8>, String> {
    let cpus = machine
        .cpus
        .iter()
        .map(ComputerCpuContext::snapshot_record)
        .collect::<Vec<_>>();
    let devices = device_snapshot_records(machine);
    snapshot::encode_snapshot_v1(machine.memory().bytes(), machine.boot_cpu, &cpus, &devices)
}

pub(super) fn restore_ram_snapshot_v1(
    profile: ComputerMachineProfile,
    snapshot_bytes: &[u8],
) -> Result<ComputerMachine, String> {
    let snapshot = snapshot::decode_snapshot_v1(snapshot_bytes)?;
    snapshot::validate_snapshot_ram_matches_profile(&profile, &snapshot)?;
    let mut machine = ComputerMachine::from_profile(profile).map_err(|error| error.to_string())?;
    machine.write_guest_ram_bytes(0, snapshot.ram)?;
    Ok(machine)
}

pub(super) fn restore_snapshot_v1(
    profile: ComputerMachineProfile,
    snapshot_bytes: &[u8],
) -> Result<ComputerMachine, String> {
    let snapshot = snapshot::decode_snapshot_v1(snapshot_bytes)?;
    snapshot::validate_snapshot_ram_matches_profile(&profile, &snapshot)?;
    let mut machine = ComputerMachine::from_profile(profile).map_err(|error| error.to_string())?;
    machine.write_guest_ram_bytes(0, snapshot.ram)?;
    machine.cpus = snapshot
        .cpus
        .iter()
        .cloned()
        .map(ComputerCpuContext::from_snapshot_record)
        .collect::<Result<Vec<_>, _>>()?;
    for device in snapshot.devices {
        restore_device_snapshot_record(&mut machine, device)?;
    }
    machine.boot_cpu = restore_boot_cpu_id(snapshot.header.boot_cpu_id, machine.cpus.len())?;
    Ok(machine)
}

fn restore_boot_cpu_id(
    boot_cpu_id: Option<u32>,
    cpu_count: usize,
) -> Result<Option<usize>, String> {
    boot_cpu_id
        .map(|id| {
            let id = usize::try_from(id)
                .map_err(|_| "ComputerMachine snapshot boot CPU id does not fit usize")?;
            if id >= cpu_count {
                return Err("ComputerMachine snapshot boot CPU id is outside CPU table".to_string());
            }
            Ok(id)
        })
        .transpose()
}

fn device_snapshot_records(machine: &ComputerMachine) -> Vec<ComputerDeviceSnapshotRecord> {
    let mut devices = snapshot_device_records(&machine.devices, &machine.bus);
    let address_spaces = machine.address_spaces.snapshot();
    let cpu_modes = machine
        .cpus
        .iter()
        .enumerate()
        .filter_map(|(cpu_index, cpu)| {
            let mode = cpu.mode_snapshot();
            (mode != default_cpu_mode_snapshot()).then_some(K16CpuModeSnapshotRecord {
                cpu_index: cpu_index as u32,
                mode,
            })
        })
        .collect::<Vec<_>>();
    if !address_spaces.spaces.is_empty() || !cpu_modes.is_empty() {
        devices.push(ComputerDeviceSnapshotRecord::Mmu0 {
            address_spaces,
            cpu_modes,
        });
    }
    devices
}

fn restore_device_snapshot_record(
    machine: &mut ComputerMachine,
    record: ComputerDeviceSnapshotRecord,
) -> Result<(), String> {
    match record {
        ComputerDeviceSnapshotRecord::Mmu0 {
            address_spaces,
            cpu_modes,
        } => {
            machine.address_spaces =
                MmuAddressSpaces::from_snapshot(machine.memory().len() as u32, address_spaces)?;
            for cpu_mode in cpu_modes {
                validate_cpu_mode_snapshot(&machine.address_spaces, cpu_mode.mode)?;
                let cpu_index = usize::try_from(cpu_mode.cpu_index)
                    .map_err(|_| "ComputerMachine snapshot CPU mode index does not fit usize")?;
                let cpu = machine.cpus.get_mut(cpu_index).ok_or_else(|| {
                    format!(
                        "ComputerMachine snapshot CPU mode index {} is outside CPU table",
                        cpu_mode.cpu_index
                    )
                })?;
                cpu.restore_mode_snapshot(cpu_mode.mode);
            }
        }
        record => {
            restore_regular_device_snapshot_record(&machine.devices, &mut machine.bus, record)?;
        }
    }
    Ok(())
}

fn validate_cpu_mode_snapshot(
    address_spaces: &MmuAddressSpaces,
    snapshot: K16CpuModeSnapshot,
) -> Result<(), String> {
    validate_address_mode_snapshot(address_spaces, snapshot.address_mode)?;
    validate_address_mode_snapshot(address_spaces, snapshot.trap_address_mode)?;
    Ok(())
}

fn validate_address_mode_snapshot(
    address_spaces: &MmuAddressSpaces,
    mode: K16AddressMode,
) -> Result<(), String> {
    let K16AddressMode::Translated { address_space } = mode else {
        return Ok(());
    };
    address_spaces.get(address_space).ok_or_else(|| {
        format!(
            "ComputerMachine snapshot CPU mode references missing MMU address-space id {}",
            address_space.raw()
        )
    })?;
    Ok(())
}

fn default_cpu_mode_snapshot() -> K16CpuModeSnapshot {
    K16CpuModeSnapshot {
        address_mode: K16AddressMode::Physical,
        privilege_mode: K16PrivilegeMode::Kernel,
        trap_address_mode: K16AddressMode::Physical,
        trap_privilege_mode: K16PrivilegeMode::Kernel,
    }
}

impl ComputerCpuContext {
    fn snapshot_record(&self) -> ComputerCpuSnapshotRecord {
        match self {
            ComputerCpuContext::K16 { cpu, max_steps, .. } => ComputerCpuSnapshotRecord::K16 {
                cpu: cpu.snapshot(),
                max_steps: *max_steps,
            },
        }
    }

    fn from_snapshot_record(record: ComputerCpuSnapshotRecord) -> Result<Self, String> {
        match record {
            ComputerCpuSnapshotRecord::K16 { cpu, max_steps } => {
                if max_steps == 0 {
                    return Err(
                        "ComputerMachine snapshot K16 CPU max_steps must be non-zero".to_string(),
                    );
                }
                Ok(ComputerCpuContext::K16 {
                    cpu: K16Cpu::from_snapshot(cpu),
                    decoder: K16CachedDecoder::new(),
                    max_steps,
                })
            }
        }
    }

    fn mode_snapshot(&self) -> K16CpuModeSnapshot {
        match self {
            ComputerCpuContext::K16 { cpu, .. } => cpu.mode_snapshot(),
        }
    }

    fn restore_mode_snapshot(&mut self, snapshot: K16CpuModeSnapshot) {
        match self {
            ComputerCpuContext::K16 { cpu, .. } => cpu.restore_mode_snapshot(snapshot),
        }
    }
}
