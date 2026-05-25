use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_volume_create_writes_empty_ruxvol_header() {
    let path = temp_file("create-storage0.ruxvol");
    let output = Command::new(rux_binary())
        .args(["volume", "create", path.to_str().unwrap(), "--size", "4096"])
        .output()
        .expect("rux runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..8], b"RUXVOL1\0");
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 4096);
    assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 0);
    assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 0);
    assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 0);
}

#[test]
fn rux_volume_put_boot_records_boot_artifact() {
    let volume_path = temp_file("boot-storage0.ruxvol");
    let boot_path = temp_file("boot.bin");
    fs::write(&boot_path, [0x01, 0x02, 0x03, 0x04]).expect("boot writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "4096",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-boot runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&volume_path).expect("volume reads");
    let offset = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
    let size = u32::from_le_bytes(bytes[16..20].try_into().unwrap()) as usize;
    let checksum = u32::from_le_bytes(bytes[20..24].try_into().unwrap());
    assert_eq!(size, 4);
    assert_eq!(checksum, 10);
    assert_eq!(&bytes[offset..offset + size], &[0x01, 0x02, 0x03, 0x04]);
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-volume-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}
