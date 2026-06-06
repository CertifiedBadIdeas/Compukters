use super::{ComputerCpuContext, ComputerMachine};
use crate::computer::devices::StoragePortControllerSnapshot;
use crate::computer::profile::ComputerMachineProfile;
use crate::computer::snapshot;
use crate::computer::snapshot::{ComputerCpuSnapshotRecord, ComputerDeviceSnapshotRecord};
use crate::k16::K16Cpu;

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
    let mut devices = Vec::new();
    if let Some(control) = machine.control_device() {
        devices.push(ComputerDeviceSnapshotRecord::Control {
            status: control.status,
            panic_code: control.panic_code,
            exit_code: control.exit_code,
        });
    }
    if let Some(debug) = machine.debug_device() {
        devices.push(ComputerDeviceSnapshotRecord::DebugSerial {
            bytes: debug.bytes().to_vec(),
        });
    }
    if let Some(display0) = machine.display0_device() {
        devices.push(ComputerDeviceSnapshotRecord::Display0 {
            snapshot: display0.snapshot(),
        });
    }
    if let Some(serial_input) = machine.serial_input_device() {
        devices.push(ComputerDeviceSnapshotRecord::SerialInput {
            bytes: serial_input.bytes(),
        });
    }
    if let Some(storage0) = machine.storage0_device() {
        let snapshot = storage0.controller_snapshot();
        devices.push(ComputerDeviceSnapshotRecord::Storage0 {
            status: snapshot.status,
            error: snapshot.error,
            lba_low: snapshot.lba_low,
            lba_high: snapshot.lba_high,
            block_count: snapshot.block_count,
            buffer_addr: snapshot.buffer_addr,
            bytes_done: snapshot.bytes_done,
            sequence: snapshot.sequence,
        });
    }
    if let Some(game_ticks) = machine.timer0_game_ticks() {
        devices.push(ComputerDeviceSnapshotRecord::Timer0 { game_ticks });
    }
    devices
}

fn restore_device_snapshot_record(
    machine: &mut ComputerMachine,
    record: ComputerDeviceSnapshotRecord,
) -> Result<(), String> {
    match record {
        ComputerDeviceSnapshotRecord::Control {
            status,
            panic_code,
            exit_code,
        } => {
            let control = machine.control_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains control device state but profile has no control device"
                    .to_string()
            })?;
            control.status = status;
            control.panic_code = panic_code;
            control.exit_code = exit_code;
        }
        ComputerDeviceSnapshotRecord::DebugSerial { bytes } => {
            let debug = machine.debug_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains debug device state but profile has no debug device"
                    .to_string()
            })?;
            debug.restore_bytes(bytes);
        }
        ComputerDeviceSnapshotRecord::Display0 { snapshot } => {
            let display0 = machine.display0_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains display0 device state but profile has no display0 device"
                    .to_string()
            })?;
            display0.restore_snapshot(snapshot)?;
        }
        ComputerDeviceSnapshotRecord::SerialInput { bytes } => {
            let serial_input = machine.serial_input_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains serial input device state but profile has no serial input device"
                    .to_string()
            })?;
            serial_input.restore_bytes(bytes);
        }
        ComputerDeviceSnapshotRecord::Storage0 {
            status,
            error,
            lba_low,
            lba_high,
            block_count,
            buffer_addr,
            bytes_done,
            sequence,
        } => {
            let storage0 = machine.storage0_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains storage0 device state but profile has no storage0 device"
                    .to_string()
            })?;
            storage0.restore_controller_snapshot(StoragePortControllerSnapshot {
                status,
                error,
                lba_low,
                lba_high,
                block_count,
                buffer_addr,
                bytes_done,
                sequence,
            });
        }
        ComputerDeviceSnapshotRecord::Timer0 { game_ticks } => {
            let timer0 = machine.timer0_device_mut().ok_or_else(|| {
                "ComputerMachine snapshot contains timer0 device state but profile has no timer0 device"
                    .to_string()
            })?;
            timer0.restore_game_ticks(game_ticks);
        }
    }
    Ok(())
}

impl ComputerCpuContext {
    fn snapshot_record(&self) -> ComputerCpuSnapshotRecord {
        match self {
            ComputerCpuContext::K16 { cpu, max_steps } => ComputerCpuSnapshotRecord::K16 {
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
                    max_steps,
                })
            }
        }
    }
}
