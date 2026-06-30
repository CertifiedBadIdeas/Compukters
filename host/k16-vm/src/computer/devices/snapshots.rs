use super::ComputerDeviceIds;
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
