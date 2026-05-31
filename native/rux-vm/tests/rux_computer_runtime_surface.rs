use std::fs;
use std::path::Path;

#[test]
fn rux_computer_handle_source_does_not_expose_low_image_startup_or_handoff() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let lib_source = fs::read_to_string(manifest_dir.join("src/lib.rs")).unwrap();
    let computer_mod_source = fs::read_to_string(manifest_dir.join("src/computer/mod.rs")).unwrap();
    let handle_source = fs::read_to_string(manifest_dir.join("src/computer/handle.rs")).unwrap();
    let jni_source = fs::read_to_string(manifest_dir.join("src/jni.rs")).unwrap();

    assert!(lib_source.contains("pub mod k16_computer"));
    assert!(!lib_source.contains("pub mod rux_computer"));
    assert!(computer_mod_source.contains("K16ComputerHandle"));
    assert!(!computer_mod_source.contains("RuxComputerHandle"));
    assert!(handle_source.contains("pub struct K16ComputerHandle"));
    assert!(!handle_source.contains("pub struct RuxComputerHandle"));
    assert!(!handle_source.contains("pub fn create("));
    assert!(!handle_source.contains("create_with_storage0_media"));
    assert!(!handle_source.contains("create_with_storage0_path"));
    assert!(!handle_source.contains("boot_handoff_ruxi_from_guest_ram"));
    assert!(!jni_source.contains("createLowImageNative"));
    assert!(!jni_source.contains("createRuxComputerNative"));
    assert!(!jni_source.contains("runRuxComputerUntilSignalNative"));

    for required_name in [
        "createK16ComputerFromBiosFlashNative",
        "restoreK16ComputerFromBiosFlashSnapshotNative",
        "runK16ComputerUntilSignalNative",
        "k16ComputerControlNative",
        "k16ComputerDebugOutputNative",
        "drainK16ComputerDebugOutputNative",
        "k16ComputerDisplay0SnapshotNative",
        "k16ComputerStorage0MediaSnapshotNative",
        "k16ComputerMachineSnapshotNative",
        "pushK16ComputerSerialInputNative",
        "freeK16ComputerNative",
    ] {
        assert!(
            jni_source.contains(required_name),
            "src/jni.rs should expose {required_name}"
        );
    }

    for legacy_name in [
        "createRuxComputerFromBiosFlashNative",
        "restoreRuxComputerFromBiosFlashSnapshotNative",
        "runRux16ComputerUntilSignalNative",
        "ruxComputerControlNative",
        "ruxComputerDebugOutputNative",
        "drainRuxComputerDebugOutputNative",
        "ruxComputerDisplay0SnapshotNative",
        "ruxComputerStorage0MediaSnapshotNative",
        "ruxComputerMachineSnapshotNative",
        "pushRuxComputerSerialInputNative",
        "freeRuxComputerNative",
    ] {
        assert!(
            !jni_source.contains(legacy_name),
            "src/jni.rs should not expose {legacy_name}"
        );
    }
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

#[test]
fn active_abi_docs_do_not_present_low_image_as_supported() {
    let repo_dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("repo root is above native/rux-vm");
    let abi_dir = repo_dir.join("docs/abi");
    let mut docs = String::new();
    collect_text_files(&abi_dir, &mut docs);

    assert!(!abi_dir.join("fixtures").exists());
    for removed_doc in [
        "QUICKSTART.md",
        "FREEZE-CHECKLIST.md",
        "PRE-FREEZE-GAPS.md",
        "cpp-frontend-notes.md",
        "rux-low-errors-v1.md",
        "rux-low-image-v1.md",
        "rux-low-image-v1-opcodes.json",
        "rux-machine-profile-v1.md",
    ] {
        assert!(!abi_dir.join(removed_doc).exists());
    }

    for forbidden in ["RUXI", "LowImage", "low image", "low-image", ".ruxi"] {
        assert!(!docs.contains(forbidden));
    }
}

fn collect_text_files(dir: &Path, output: &mut String) {
    for entry in fs::read_dir(dir).expect("directory reads") {
        let entry = entry.expect("directory entry reads");
        let path = entry.path();
        if path.is_dir() {
            collect_text_files(&path, output);
        } else if path
            .extension()
            .is_some_and(|extension| extension == "md" || extension == "json")
        {
            output.push_str(&fs::read_to_string(path).expect("text file reads"));
            output.push('\n');
        }
    }
}
