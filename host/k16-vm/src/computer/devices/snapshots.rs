use super::ComputerDeviceIds;
use super::StoragePortControllerSnapshot;
use crate::computer::snapshot::ComputerDeviceSnapshotRecord;
use crate::low_bus::MachineBus;

pub(crate) fn snapshot_device_records(
    devices: &ComputerDeviceIds,
    bus: &MachineBus,
) -> Vec<ComputerDeviceSnapshotRecord> {
    let mut records = Vec::new();
    if let Some(control) = devices.control(bus) {
        records.push(ComputerDeviceSnapshotRecord::Control {
            status: control.status,
            panic_code: control.panic_code,
            exit_code: control.exit_code,
            os_stats_addr: control
                .os_stats_region()
                .map(|(addr, _)| addr)
                .unwrap_or_default(),
            os_stats_size: control
                .os_stats_region()
                .map(|(_, size)| size)
                .unwrap_or_default(),
        });
    }
    if let Some(debug) = devices.debug_serial(bus) {
        records.push(ComputerDeviceSnapshotRecord::DebugSerial {
            bytes: debug.bytes().to_vec(),
        });
    }
    if let Some(serial_input) = devices.serial_input(bus) {
        records.push(ComputerDeviceSnapshotRecord::SerialInput {
            bytes: serial_input.bytes(),
        });
    }
    if let Some(storage0) = devices.storage0(bus) {
        let snapshot = storage0.controller_snapshot();
        records.push(ComputerDeviceSnapshotRecord::Storage0 {
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
    if let Some(timer0) = devices.timer0(bus) {
        records.push(ComputerDeviceSnapshotRecord::Timer0 {
            game_ticks: timer0.game_ticks(),
        });
    }
    if let Some(keyboard0) = devices.keyboard0(bus) {
        records.push(ComputerDeviceSnapshotRecord::Keyboard0 {
            events: keyboard0.events(),
            sequence: keyboard0.sequence(),
            dropped_count: keyboard0.dropped_count(),
        });
    }
    records
}

pub(crate) fn restore_device_snapshot_record(
    devices: &ComputerDeviceIds,
    bus: &mut MachineBus,
    record: ComputerDeviceSnapshotRecord,
) -> Result<(), String> {
    match record {
        ComputerDeviceSnapshotRecord::Control {
            status,
            panic_code,
            exit_code,
            os_stats_addr,
            os_stats_size,
        } => {
            let control = devices.control_mut(bus).ok_or_else(|| {
                "ComputerMachine snapshot contains control device state but profile has no control device"
                    .to_string()
            })?;
            control.status = status;
            control.panic_code = panic_code;
            control.exit_code = exit_code;
            control.restore_os_stats_region(os_stats_addr, os_stats_size);
            Ok(())
        }
        ComputerDeviceSnapshotRecord::DebugSerial { bytes } => {
            let debug = devices.debug_serial_mut(bus).ok_or_else(|| {
                "ComputerMachine snapshot contains debug device state but profile has no debug device"
                    .to_string()
            })?;
            debug.restore_bytes(bytes);
            Ok(())
        }
        ComputerDeviceSnapshotRecord::SerialInput { bytes } => {
            let serial_input = devices.serial_input_mut(bus).ok_or_else(|| {
                "ComputerMachine snapshot contains serial input device state but profile has no serial input device"
                    .to_string()
            })?;
            serial_input.restore_bytes(bytes);
            Ok(())
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
            let storage0 = devices.storage0_mut(bus).ok_or_else(|| {
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
            Ok(())
        }
        ComputerDeviceSnapshotRecord::Timer0 { game_ticks } => {
            let timer0 = devices.timer0_mut(bus).ok_or_else(|| {
                "ComputerMachine snapshot contains timer0 device state but profile has no timer0 device"
                    .to_string()
            })?;
            timer0.restore_game_ticks(game_ticks);
            Ok(())
        }
        ComputerDeviceSnapshotRecord::Keyboard0 {
            events,
            sequence,
            dropped_count,
        } => {
            let keyboard0 = devices.keyboard0_mut(bus).ok_or_else(|| {
                "ComputerMachine snapshot contains keyboard0 device state but profile has no keyboard0 device"
                    .to_string()
            })?;
            keyboard0.restore_snapshot(events, sequence, dropped_count)
        }
        ComputerDeviceSnapshotRecord::Mmu0 { .. } => {
            Err("Mmu0 snapshot state must be restored by machine snapshot flow".to_string())
        }
    }
}
