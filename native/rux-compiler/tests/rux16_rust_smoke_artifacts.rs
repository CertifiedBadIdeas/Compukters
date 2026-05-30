use std::fs;
use std::path::Path;

#[test]
fn rust_nocore_smoke_artifacts_are_documented_and_strict() {
    let root = repo_root();
    let target_spec = root.join("tools/rux16-unknown-ruxos.json");
    let llvm_smoke_script = root.join("tools/rux16-llvm-smoke.sh");
    let clang_smoke_script = root.join("tools/rux16-clang-smoke.sh");
    let smoke_script = root.join("tools/rux16-rust-nocore-smoke.sh");
    let bootstrap_probe = root.join("tools/rux16-rustc-bootstrap-probe.sh");
    let runtime_helpers = root.join("native/rux-compiler/runtime/rux16_memory_helpers.rs");
    let docs = root.join("docs/toolchains/rux16-rust-nocore-smoke.md");
    let bootstrap_docs = root.join("docs/toolchains/rux16-rustc-bootstrap.md");

    let spec = fs::read_to_string(&target_spec).expect("Rux16 Rust target spec exists");
    assert!(spec.contains("\"llvm-target\": \"rux16\""));
    assert!(spec.contains("\"panic-strategy\": \"abort\""));
    assert!(spec.contains("\"target-pointer-width\": 32"));
    assert!(!spec.contains("\"target-pointer-width\": \"32\""));

    let llvm_smoke = fs::read_to_string(&llvm_smoke_script).expect("LLVM smoke script exists");
    assert!(llvm_smoke.contains("--bin k16"));
    assert!(llvm_smoke.contains("main.kx"));
    assert!(llvm_smoke.contains("call-helper.kx"));
    assert!(llvm_smoke.contains("stack-local-main.kx"));
    assert!(!llvm_smoke.contains("--bin rux"));
    assert!(!llvm_smoke.contains(".ruxe"));

    let clang_smoke = fs::read_to_string(&clang_smoke_script).expect("Clang smoke script exists");
    assert!(clang_smoke.contains("--bin k16"));
    assert!(clang_smoke.contains("main.kx"));
    assert!(!clang_smoke.contains("--bin rux"));
    assert!(!clang_smoke.contains(".ruxe"));

    let script = fs::read_to_string(&smoke_script).expect("Rust no_core smoke script exists");
    assert!(script.contains("#![no_core]"));
    assert!(script.contains("#![no_main]"));
    assert!(script.contains("meta_sized"));
    assert!(script.contains("pointee_sized"));
    assert!(script.contains("rux16-memory-helpers"));
    assert!(script.contains("\"$WORK_DIR/helpers.o\""));
    assert!(script.contains("main.kx"));
    assert!(script.contains("debug_bytes=2a"));
    assert!(script.contains("--bin k16"));
    assert!(!script.contains("--bin rux"));
    assert!(!script.contains(".ruxe"));
    assert!(!script.contains("|| true"));

    let helpers = fs::read_to_string(&runtime_helpers).expect("Rux16 runtime helper source exists");
    assert!(helpers.contains("#![no_core]"));
    assert!(helpers.contains("#![no_main]"));
    assert!(helpers.contains("__rux16_memcpy"));
    assert!(helpers.contains("__rux16_memset"));
    assert!(helpers.contains("__rux16_memmove"));
    assert!(!helpers.contains("extern crate std"));

    let probe = fs::read_to_string(&bootstrap_probe).expect("Rust bootstrap probe script exists");
    assert!(probe.contains("toolchains/Compukter-Kraft-rust"));
    assert!(probe.contains("toolchains/Compukter-Kraft-llvm/build-rux-min/bin/llvm-config"));
    assert!(probe.contains("build/rux16"));
    assert!(probe.contains("--targets-built"));
    assert!(probe.contains("rux16"));
    assert!(probe.contains("x.py"));
    assert!(!probe.contains("|| true"));

    let docs = fs::read_to_string(&docs).expect("Rust no_core smoke docs exist");
    assert!(docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(docs.contains("custom rustc"));
    assert!(docs.contains("KX"));
    assert!(docs.contains("debug_bytes=2a"));
    assert!(!docs.contains(".ruxe"));

    let bootstrap_docs = fs::read_to_string(&bootstrap_docs).expect("Rust bootstrap docs exist");
    assert!(bootstrap_docs.contains("tools/rux16-rustc-bootstrap-probe.sh"));
    assert!(bootstrap_docs.contains("build-rux-min/bin/llvm-config"));
    assert!(bootstrap_docs.contains("rux16"));
}

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("native/rux-compiler has repo root grandparent")
}
