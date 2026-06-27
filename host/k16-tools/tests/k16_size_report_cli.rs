use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

static TEMP_FILE_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[test]
fn k16_size_report_prints_program_totals_and_repeated_contributors() {
    let cat_map = temp_file("cat.map");
    let ls_map = temp_file("ls.map");
    fs::write(
        &cat_map,
        "\
K16 link map target=program-dynamic load_addr=0x00000000 payload_bytes=120 memory_bytes=132 retained_sections=3
section offset=0x00000000 class=text file_bytes=40 memory_bytes=40 object=/tmp/cat/k16-unknown-kraftos/release/deps/k16_cat-aaaa.rcgu.o name=.text.k16.main
section offset=0x00000028 class=text file_bytes=34 memory_bytes=34 object=/tmp/cat/k16-startup.o name=.text.k16
section offset=0x0000004a class=text file_bytes=46 memory_bytes=58 object=/tmp/cat/k16-unknown-kraftos/release/deps/libcore-deadbeef.rlib(/7) name=.text.k16.core_fmt
",
    )
    .expect("cat map writes");
    fs::write(
        &ls_map,
        "\
K16 link map target=program-dynamic load_addr=0x00000000 payload_bytes=110 memory_bytes=110 retained_sections=3
section offset=0x00000000 class=text file_bytes=30 memory_bytes=30 object=/tmp/ls/k16-unknown-kraftos/release/deps/k16_ls-bbbb.rcgu.o name=.text.k16.main
section offset=0x0000001e class=text file_bytes=34 memory_bytes=34 object=/tmp/ls/k16-startup.o name=.text.k16
section offset=0x00000040 class=text file_bytes=46 memory_bytes=46 object=/tmp/ls/k16-unknown-kraftos/release/deps/libcore-feedface.rlib(/7) name=.text.k16.core_fmt
",
    )
    .expect("ls map writes");

    let output = Command::new(k16_binary())
        .args([
            "size-report",
            cat_map.to_str().unwrap(),
            ls_map.to_str().unwrap(),
        ])
        .output()
        .expect("k16 size-report runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("stdout is UTF-8"),
        "\
K16 userland size report programs=2 total_payload_bytes=230 total_memory_bytes=242

program payload_bytes memory_bytes retained_sections name
120 132 3 cat
110 110 3 ls

duplicate_file_bytes program_count class contributor section
92 2 text libcore.rlib(/7) .text.k16.core_fmt
68 2 text k16-startup.o .text.k16
"
    );
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}

fn temp_file(name: &str) -> PathBuf {
    let id = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("k16-size-report-cli-{id}"));
    fs::create_dir_all(&dir).expect("temp dir creates");
    dir.join(name)
}
