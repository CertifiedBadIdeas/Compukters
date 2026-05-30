use std::fs;
use std::path::Path;

#[test]
fn rust_nocore_smoke_artifacts_are_documented_and_strict() {
    let root = repo_root();
    let target_spec = root.join("tools/rux16-unknown-ruxos.json");
    let smoke_script = root.join("tools/rux16-rust-nocore-smoke.sh");
    let docs = root.join("docs/toolchains/rux16-rust-nocore-smoke.md");

    let spec = fs::read_to_string(&target_spec).expect("Rux16 Rust target spec exists");
    assert!(spec.contains("\"llvm-target\": \"rux16\""));
    assert!(spec.contains("\"panic-strategy\": \"abort\""));
    assert!(spec.contains("\"target-pointer-width\": 32"));
    assert!(!spec.contains("\"target-pointer-width\": \"32\""));

    let script = fs::read_to_string(&smoke_script).expect("Rust no_core smoke script exists");
    assert!(script.contains("#![no_core]"));
    assert!(script.contains("#![no_main]"));
    assert!(script.contains("debug_bytes=2a"));
    assert!(!script.contains("|| true"));

    let docs = fs::read_to_string(&docs).expect("Rust no_core smoke docs exist");
    assert!(docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(docs.contains("custom rustc"));
    assert!(docs.contains("debug_bytes=2a"));
}

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("native/rux-compiler has repo root grandparent")
}
