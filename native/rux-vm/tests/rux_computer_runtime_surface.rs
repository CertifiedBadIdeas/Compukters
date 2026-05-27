use std::fs;
use std::path::Path;

#[test]
fn rux_computer_handle_source_does_not_expose_low_image_startup_or_handoff() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let handle_source = fs::read_to_string(manifest_dir.join("src/computer/handle.rs")).unwrap();
    let jni_source = fs::read_to_string(manifest_dir.join("src/jni.rs")).unwrap();

    assert!(!handle_source.contains("pub fn create("));
    assert!(!handle_source.contains("create_with_storage0_media"));
    assert!(!handle_source.contains("create_with_storage0_path"));
    assert!(!handle_source.contains("boot_handoff_ruxi_from_guest_ram"));
    assert!(!jni_source.contains("createLowImageNative"));
    assert!(!jni_source.contains("createRuxComputerNative"));
    assert!(!jni_source.contains("runRuxComputerUntilSignalNative"));
}

#[test]
fn computer_machine_source_does_not_expose_low_image_cpu_path() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let machine_source = fs::read_to_string(manifest_dir.join("src/computer/machine.rs")).unwrap();

    assert!(!machine_source.contains("LowImage"));
    assert!(!machine_source.contains("LowCpuContext"));
    assert!(!machine_source.contains("LowImageVm"));
    assert!(!machine_source.contains("spawn_boot_cpu"));
    assert!(!machine_source.contains("spawn_cpu("));
    assert!(!machine_source.contains("run_boot_cpu_until_signal"));
    assert!(!machine_source.contains("run_cpu_until_signal"));
}

#[test]
fn runtime_source_does_not_expose_low_image_microcontroller_machine() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let lib_source = fs::read_to_string(manifest_dir.join("src/lib.rs")).unwrap();

    assert!(!manifest_dir.join("src/microcontroller_machine.rs").exists());
    assert!(!lib_source.contains("microcontroller_machine"));
}

#[test]
fn runtime_source_does_not_expose_low_image_modules() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let lib_source = fs::read_to_string(manifest_dir.join("src/lib.rs")).unwrap();

    for removed_path in [
        "src/low_image.rs",
        "src/low_image_runner.rs",
        "src/low_disasm.rs",
    ] {
        assert!(!manifest_dir.join(removed_path).exists());
    }

    for forbidden in ["low_image", "low_image_runner", "low_disasm", "LowImage"] {
        assert!(!lib_source.contains(forbidden));
    }
}
