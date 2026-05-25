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
