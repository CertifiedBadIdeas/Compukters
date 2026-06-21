use k16_tools::k16e;
use k16_tools::k16fs;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

static TEMP_FILE_COUNTER: AtomicUsize = AtomicUsize::new(0);
const TEST_VOLUME_SIZE: &str = "1048576";
const TEST_BOOT_BLOCKS: u32 = 256;
const TEST_ROOT_START_LBA: u32 = 257;
const TEST_ROOT_BLOCKS: u32 = 1_791;
const TEST_BOOT_BYTES: u32 = TEST_BOOT_BLOCKS * 512;
const TEST_ROOT_BYTES: u32 = TEST_ROOT_BLOCKS * 512;
const TEST_TOTAL_BLOCKS: u32 = 2_048;

#[test]
fn k16_inspect_identifies_partitioned_volume() {
    let path = temp_file("storage0.kv");
    let media_path = temp_file("storage0-media.bin");
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "init",
            path.to_str().unwrap(),
            "--size",
            TEST_VOLUME_SIZE
        ])
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
        format!(
            "kind=K16VOL\nK16VOL v1 payload={TEST_VOLUME_SIZE}\nK16PT v1 entries=2\nBOOT start_lba=1 blocks={TEST_BOOT_BLOCKS} bytes={TEST_BOOT_BYTES} name=boot\nROOT start_lba={TEST_ROOT_START_LBA} blocks={TEST_ROOT_BLOCKS} bytes={TEST_ROOT_BYTES} name=root\n"
        ),
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
        format!(
            "kind=K16PT\nK16PT v1 entries=2 media_blocks={TEST_TOTAL_BLOCKS}\nBOOT start_lba=1 blocks={TEST_BOOT_BLOCKS} bytes={TEST_BOOT_BYTES} name=boot\nROOT start_lba={TEST_ROOT_START_LBA} blocks={TEST_ROOT_BLOCKS} bytes={TEST_ROOT_BYTES} name=root\n"
        ),
    );
}

#[test]
fn k16_inspect_identifies_standalone_k16fs_and_k16e() {
    let fs_path = temp_file("root.kfs");
    let k16e_path = temp_file("init.kx");
    fs::write(
        &fs_path,
        k16fs::format_empty_filesystem(32).expect("K16FS formats"),
    )
    .expect("K16FS writes");
    fs::write(
        &k16e_path,
        k16e::encode_k16_executable(
            &[0x01, 0x00],
            k16e::K16eAbiKind::Program,
            0x1_5000,
            0x1_5000,
        )
        .expect("K16E encodes"),
    )
    .expect("K16E writes");

    let fs_output = Command::new(k16_binary())
        .args(["inspect", fs_path.to_str().unwrap()])
        .output()
        .expect("k16 inspect K16FS runs");
    assert!(
        fs_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fs_output.stderr)
    );
    assert_eq!(
        String::from_utf8(fs_output.stdout).expect("K16FS inspect stdout is UTF-8"),
        "kind=K16FS\nK16FS v1 blocks=32 block_size=512 root_inode=1 inode_table_blocks=8\n",
    );

    let k16e_output = Command::new(k16_binary())
        .args(["inspect", k16e_path.to_str().unwrap()])
        .output()
        .expect("k16 inspect K16E runs");
    assert!(
        k16e_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&k16e_output.stderr)
    );
    assert_eq!(
        String::from_utf8(k16e_output.stdout).expect("K16E inspect stdout is UTF-8"),
        "kind=K16E\nK16E abi=program entry_pc=0x00015000 load_addr=0x00015000 payload_bytes=2\n",
    );
}

#[test]
fn k16_inspect_identifies_dynamic_k16e_program_size() {
    let path = temp_file("dynamic-init.kx");
    fs::write(
        &path,
        k16e::encode_dynamic_k16_program(
            &[0x01, 0x00, 0x02, 0x00],
            8,
            0,
            &[
                k16e::K16eRelocation {
                    offset: 0,
                    kind: k16e::K16eRelocationKind::Abs32,
                },
                k16e::K16eRelocation {
                    offset: 4,
                    kind: k16e::K16eRelocationKind::Call32,
                },
            ],
        )
        .expect("dynamic K16E encodes"),
    )
    .expect("dynamic K16E writes");

    let output = Command::new(k16_binary())
        .args(["inspect", path.to_str().unwrap()])
        .output()
        .expect("k16 inspect dynamic K16E runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("dynamic K16E stdout is UTF-8"),
        "kind=K16E\nK16E abi=program dynamic=true entry_offset=0x00000000 payload_bytes=4 memory_bytes=8 relocations=2 relocation_bytes=16\n",
    );
}

#[test]
fn k16_inspect_rejects_unknown_blob_without_fallback() {
    let path = temp_file("unknown.bin");
    fs::write(&path, b"not a k16 blob").expect("unknown blob writes");

    let output = Command::new(k16_binary())
        .args(["inspect", path.to_str().unwrap()])
        .output()
        .expect("k16 inspect runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unrecognized K16 blob magic"),
        "stderr: {stderr}"
    );
}

fn temp_file(name: &str) -> PathBuf {
    let counter = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let path = std::env::temp_dir().join(format!(
        "k16-inspect-{}-{counter}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
