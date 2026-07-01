use std::fs;
use std::path::{Path, PathBuf};

fn source_path(relative: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join(relative)
}

fn read_source(relative: &str) -> String {
    fs::read_to_string(source_path(relative)).expect("source is readable")
}

#[test]
fn source_level_architecture_guards_live_outside_machine_unit_tests() {
    let unit_tests = read_source("src/computer/machine/tests.rs");

    for guard in [
        "computer_machine_uses_device_id_facade_instead_of_per_device_id_fields",
        "computer_machine_memory_map_and_stats_use_device_descriptor_facade",
        "computer_machine_typed_accessors_use_device_id_facade",
        "computer_device_ids_encapsulates_fields_and_lifecycle_registration",
        "snapshot_flow_delegates_regular_device_snapshot_records_to_device_facade",
        "snapshot_flow_delegates_regular_device_snapshot_restore_to_device_facade",
    ] {
        assert!(
            !unit_tests.contains(guard),
            "source-level architecture guard should live in tests/computer_machine_architecture.rs, not src/computer/machine/tests.rs: {guard}",
        );
    }
}

#[test]
fn computer_machine_uses_device_id_facade_instead_of_per_device_id_fields() {
    let source = read_source("src/computer/machine.rs");
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
fn computer_machine_memory_map_and_stats_use_device_descriptor_facade() {
    let source = read_source("src/computer/machine.rs");
    let memory_map_body = source
        .split("pub fn memory_map(&self) -> ComputerMemoryMap {")
        .nth(1)
        .and_then(|tail| tail.split("pub fn snapshot_v1").next())
        .expect("memory_map body is present");
    let stats_snapshot_body = source
        .split("pub fn stats_snapshot(&self) -> K16ComputerStatsSnapshot {")
        .nth(1)
        .and_then(|tail| tail.split("fn decode_cache_stats_snapshot").next())
        .expect("stats_snapshot body is present");

    for field in [
        "self.devices.control",
        "self.devices.debug_serial",
        "self.devices.serial_input",
        "self.devices.gpu0",
        "self.devices.storage0",
        "self.devices.timer0",
        "self.devices.keyboard0",
        "self.devices.mmu0",
        "self.devices.bios_flash",
    ] {
        assert!(
            !memory_map_body.contains(field),
            "memory_map should use ComputerDeviceIds descriptors, not {field}",
        );
        assert!(
            !stats_snapshot_body.contains(field),
            "stats_snapshot should use ComputerDeviceIds descriptors, not {field}",
        );
    }
}

#[test]
fn computer_machine_typed_accessors_use_device_id_facade() {
    let source = read_source("src/computer/machine.rs");
    let compact_source = source
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();

    for field in [
        "control",
        "debug_serial",
        "serial_input",
        "gpu0",
        "storage0",
        "timer0",
        "keyboard0",
        "mmu0",
    ] {
        let direct_lookup = format!("self.devices.{field}.and_then");
        assert!(
            !compact_source.contains(&direct_lookup),
            "ComputerMachine typed accessors should use ComputerDeviceIds helpers, not {direct_lookup}",
        );
    }
}

#[test]
fn computer_device_ids_encapsulates_fields_and_lifecycle_registration() {
    let ids_source = read_source("src/computer/devices/ids.rs");
    let ids_struct_body = ids_source
        .split("pub(crate) struct ComputerDeviceIds {")
        .nth(1)
        .and_then(|tail| tail.split("}\n\nimpl ComputerDeviceIds").next())
        .expect("ComputerDeviceIds struct body is present");
    assert!(
        !ids_struct_body.contains("pub(crate)"),
        "ComputerDeviceIds fields should be private behind facade methods",
    );

    let construction_source = read_source("src/computer/machine/construction.rs");
    assert!(
        !construction_source.contains("impl ComputerDeviceIds"),
        "ComputerDeviceIds hardware registration should live with the facade, not machine construction",
    );

    let boot_flow_source = read_source("src/computer/machine/boot_flow.rs");
    assert!(
        !boot_flow_source.contains("devices.bios_flash"),
        "boot flow should use ComputerDeviceIds bios-flash lifecycle helpers",
    );
}

#[test]
fn snapshot_flow_delegates_regular_device_snapshot_records_to_device_facade() {
    let snapshot_flow_source = read_source("src/computer/machine/snapshot_flow.rs");
    let device_snapshot_body = snapshot_flow_source
        .split("fn device_snapshot_records(machine: &ComputerMachine) -> Vec<ComputerDeviceSnapshotRecord> {")
        .nth(1)
        .and_then(|tail| tail.split("fn restore_device_snapshot_record").next())
        .expect("device_snapshot_records body is present");

    assert!(
        device_snapshot_body.contains("snapshot_device_records"),
        "snapshot_flow should delegate regular device snapshot records to the device facade",
    );
    for accessor in [
        "control_device",
        "debug_device",
        "serial_input_device",
        "storage0_device",
        "timer0_game_ticks",
        "keyboard0_device",
    ] {
        assert!(
            !device_snapshot_body.contains(accessor),
            "snapshot_flow encode path should not enumerate regular devices through machine.{accessor}",
        );
    }
}

#[test]
fn snapshot_flow_delegates_regular_device_snapshot_restore_to_device_facade() {
    let snapshot_flow_source = read_source("src/computer/machine/snapshot_flow.rs");
    let restore_body = snapshot_flow_source
        .split("fn restore_device_snapshot_record(\n")
        .nth(1)
        .and_then(|tail| tail.split("fn validate_cpu_mode_snapshot").next())
        .expect("restore_device_snapshot_record body is present");

    assert!(
        restore_body.contains("restore_regular_device_snapshot_record"),
        "snapshot_flow should delegate regular device snapshot restore to the device facade",
    );
    for accessor in [
        "control_device_mut",
        "debug_device_mut",
        "serial_input_device_mut",
        "storage0_device_mut",
        "timer0_device_mut",
        "keyboard0_device_mut",
    ] {
        assert!(
            !restore_body.contains(accessor),
            "snapshot_flow restore path should not restore regular devices through machine.{accessor}",
        );
    }
}
