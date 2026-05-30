use rux_compiler::ruxe;
use rux_compiler::ruxfs;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

static TEMP_FILE_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[test]
fn rux_inspect_identifies_partitioned_volume() {
    let path = temp_file("storage0.kv");
    let media_path = temp_file("storage0-media.bin");
    assert!(Command::new(k16_binary())
        .args(["volume", "init", path.to_str().unwrap(), "--size", "65536"])
        .status()
        .expect("volume init runs")
        .success());
    let volume_bytes = fs::read(&path).expect("volume reads");
    fs::write(&media_path, &volume_bytes[16..]).expect("media writes");

    let output = Command::new(k16_binary())
        .args(["inspect", path.to_str().unwrap()])
        .output()
        .expect("k16 inspect runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("inspect stdout is UTF-8"),
        "kind=K16VOL\nK16VOL v1 payload=65536\nRUXPT v1 entries=2\nBOOT start_lba=1 blocks=32 bytes=16384 name=boot\nROOT start_lba=33 blocks=95 bytes=48640 name=root\n",
    );

    let media_output = Command::new(k16_binary())
        .args(["inspect", media_path.to_str().unwrap()])
        .output()
        .expect("k16 inspect media runs");
    assert!(
        media_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&media_output.stderr)
    );
    assert_eq!(
        String::from_utf8(media_output.stdout).expect("media inspect stdout is UTF-8"),
        "kind=RUXPT\nRUXPT v1 entries=2 media_blocks=128\nBOOT start_lba=1 blocks=32 bytes=16384 name=boot\nROOT start_lba=33 blocks=95 bytes=48640 name=root\n",
    );
}

#[test]
fn rux_inspect_identifies_standalone_ruxfs_and_ruxe() {
    let fs_path = temp_file("root.kfs");
    let ruxe_path = temp_file("init.kx");
    fs::write(
        &fs_path,
        ruxfs::format_empty_filesystem(32).expect("RuxFS formats"),
    )
    .expect("RuxFS writes");
    fs::write(
        &ruxe_path,
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Program, 0x8000, 0x8000)
            .expect("RUXE encodes"),
    )
    .expect("RUXE writes");

    let fs_output = Command::new(k16_binary())
        .args(["inspect", fs_path.to_str().unwrap()])
        .output()
        .expect("k16 inspect RuxFS runs");
    assert!(
        fs_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fs_output.stderr)
    );
    assert_eq!(
        String::from_utf8(fs_output.stdout).expect("RuxFS inspect stdout is UTF-8"),
        "kind=RUXFS\nRUXFS v1 blocks=32 block_size=512 root_inode=1 inode_table_blocks=8\n",
    );

    let ruxe_output = Command::new(k16_binary())
        .args(["inspect", ruxe_path.to_str().unwrap()])
        .output()
        .expect("k16 inspect RUXE runs");
    assert!(
        ruxe_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&ruxe_output.stderr)
    );
    assert_eq!(
        String::from_utf8(ruxe_output.stdout).expect("RUXE inspect stdout is UTF-8"),
        "kind=RUXE\nRUXE abi=program entry_pc=0x00008000 load_addr=0x00008000 payload_bytes=2\n",
    );
}

#[test]
fn rux_inspect_rejects_unknown_blob_without_fallback() {
    let path = temp_file("unknown.bin");
    fs::write(&path, b"not a rux blob").expect("unknown blob writes");

    let output = Command::new(k16_binary())
        .args(["inspect", path.to_str().unwrap()])
        .output()
        .expect("k16 inspect runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unrecognized Rux blob magic"),
        "stderr: {stderr}"
    );
}

fn temp_file(name: &str) -> PathBuf {
    let counter = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let path = std::env::temp_dir().join(format!(
        "rux-inspect-{}-{counter}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
