use std::fs;
use std::path::Path;

#[test]
fn rust_nocore_smoke_artifacts_are_documented_and_strict() {
    let root = repo_root();
    let target_spec = root.join("tools/k16-unknown-kraftos.json");
    let llvm_smoke_script = root.join("tools/k16-llvm-smoke.sh");
    let clang_smoke_script = root.join("tools/k16-clang-smoke.sh");
    let smoke_script = root.join("tools/k16-rust-nocore-smoke.sh");
    let bootstrap_probe = root.join("tools/k16-rustc-bootstrap-probe.sh");
    let runtime_helpers = root.join("native/k16-tools/runtime/k16_memory_helpers.rs");
    let llvm_docs = root.join("docs/toolchains/k16-llvm-smoke.md");
    let clang_docs = root.join("docs/toolchains/k16-clang-smoke.md");
    let docs = root.join("docs/toolchains/k16-rust-nocore-smoke.md");
    let bootstrap_docs = root.join("docs/toolchains/k16-rustc-bootstrap.md");
    let llvm_submodule_docs = root.join("docs/toolchains/k16-llvm-submodule.md");
    let feasibility_docs = root.join("docs/toolchains/k16-rust-feasibility.md");
    let strategy_docs = root.join("docs/toolchains/k16-language-strategy.md");
    let retired_public_paths = [
        "tools/k16-unknown-ruxos.json",
        "tools/rux16-unknown-ruxos.json",
        "tools/rux16-llvm-smoke.sh",
        "tools/rux16-clang-smoke.sh",
        "tools/rux16-rust-nocore-smoke.sh",
        "tools/rux16-rustc-bootstrap-probe.sh",
        "docs/toolchains/rux16-llvm-smoke.md",
        "docs/toolchains/rux16-clang-smoke.md",
        "docs/toolchains/rux16-llvm-submodule.md",
        "docs/toolchains/rux16-rust-feasibility.md",
        "docs/toolchains/rux16-rust-nocore-smoke.md",
        "docs/toolchains/rux16-rustc-bootstrap.md",
        "docs/toolchains/rux16-language-strategy.md",
    ];

    for path in retired_public_paths {
        assert!(
            !root.join(path).exists(),
            "machine toolchain surface should not keep retired public path `{path}`"
        );
    }

    let spec = fs::read_to_string(&target_spec).expect("K16 Rust target spec exists");
    assert!(spec.contains("\"llvm-target\": \"k16\""));
    assert!(spec.contains("\"panic-strategy\": \"abort\""));
    assert!(spec.contains("\"target-pointer-width\": 32"));
    assert!(!spec.contains("\"target-pointer-width\": \"32\""));

    let llvm_smoke = fs::read_to_string(&llvm_smoke_script).expect("LLVM smoke script exists");
    assert!(llvm_smoke.contains("--bin k16"));
    assert!(llvm_smoke.contains("main.kx"));
    assert!(llvm_smoke.contains("call-helper.kx"));
    assert!(llvm_smoke.contains("stack-local-main.kx"));
    assert!(!llvm_smoke.contains("--bin rux"));
    assert!(!llvm_smoke.contains(".k16e"));

    let clang_smoke = fs::read_to_string(&clang_smoke_script).expect("Clang smoke script exists");
    assert!(clang_smoke.contains("--bin k16"));
    assert!(clang_smoke.contains("main.kx"));
    assert!(!clang_smoke.contains("--bin rux"));
    assert!(!clang_smoke.contains(".k16e"));

    let script = fs::read_to_string(&smoke_script).expect("Rust no_core smoke script exists");
    assert!(script.contains("K16_RUSTC"));
    assert!(script.contains("K16_LLVM_BIN_DIR"));
    assert!(script.contains("K16_RUST_TARGET_JSON"));
    assert!(script.contains("tools/k16-unknown-kraftos.json"));
    assert!(script.contains("#![no_core]"));
    assert!(script.contains("#![no_main]"));
    assert!(script.contains("meta_sized"));
    assert!(script.contains("pointee_sized"));
    assert!(script.contains("k16-memory-helpers"));
    assert!(script.contains("\"$WORK_DIR/helpers.o\""));
    assert!(script.contains("main.kx"));
    assert!(script.contains("debug_bytes=2a"));
    assert!(script.contains("--bin k16"));
    assert!(!script.contains("--bin rux"));
    assert!(!script.contains(".k16e"));
    assert!(!script.contains("|| true"));
    assert!(!script.contains("RUX16_RUSTC"));
    assert!(!script.contains("RUX16_LLVM_BIN_DIR"));
    assert!(!script.contains("RUX16_RUST_TARGET_JSON"));

    let helpers = fs::read_to_string(&runtime_helpers).expect("K16 runtime helper source exists");
    assert!(helpers.contains("#![no_core]"));
    assert!(helpers.contains("#![no_main]"));
    assert!(helpers.contains("__k16_memcpy"));
    assert!(helpers.contains("__k16_memset"));
    assert!(helpers.contains("__k16_memmove"));
    assert!(!helpers.contains("extern crate std"));

    let probe = fs::read_to_string(&bootstrap_probe).expect("Rust bootstrap probe script exists");
    assert!(probe.contains("K16_RUST_SRC"));
    assert!(probe.contains("K16_LLVM_CONFIG"));
    assert!(probe.contains("K16_RUST_BUILD_DIR"));
    assert!(probe.contains("K16_RUST_HOST"));
    assert!(probe.contains("toolchains/Compukter-Kraft-rust"));
    assert!(probe.contains("toolchains/Compukter-Kraft-llvm/build-rux-min/bin/llvm-config"));
    assert!(probe.contains("build/k16"));
    assert!(probe.contains("--targets-built"));
    assert!(probe.contains("k16"));
    assert!(probe.contains("x.py"));
    assert!(!probe.contains("|| true"));
    assert!(!probe.contains("RUX16_RUST_SRC"));
    assert!(!probe.contains("RUX16_LLVM_CONFIG"));
    assert!(!probe.contains("RUX16_RUST_BUILD_DIR"));
    assert!(!probe.contains("RUX16_RUST_HOST"));

    let llvm_docs = fs::read_to_string(&llvm_docs).expect("LLVM smoke docs exist");
    assert!(llvm_docs.contains("tools/k16-llvm-smoke.sh"));
    assert!(llvm_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(!llvm_docs.contains("tools/rux16-llvm-smoke.sh"));
    assert!(!llvm_docs.contains("RUX16_LLVM_BIN_DIR"));

    let clang_docs = fs::read_to_string(&clang_docs).expect("Clang smoke docs exist");
    assert!(clang_docs.contains("tools/k16-clang-smoke.sh"));
    assert!(clang_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(!clang_docs.contains("tools/rux16-clang-smoke.sh"));
    assert!(!clang_docs.contains("RUX16_LLVM_BIN_DIR"));

    let llvm_submodule_docs =
        fs::read_to_string(&llvm_submodule_docs).expect("LLVM submodule docs exist");
    assert!(llvm_submodule_docs.contains("K16 LLVM"));
    assert!(!llvm_submodule_docs.contains("Rux16 LLVM Submodule"));

    let docs = fs::read_to_string(&docs).expect("Rust no_core smoke docs exist");
    assert!(docs.contains("tools/k16-rust-nocore-smoke.sh"));
    assert!(docs.contains("tools/k16-unknown-kraftos.json"));
    assert!(docs.contains("K16_RUSTC"));
    assert!(docs.contains("K16_LLVM_BIN_DIR"));
    assert!(docs.contains("custom rustc"));
    assert!(docs.contains("KX"));
    assert!(docs.contains("debug_bytes=2a"));
    assert!(!docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(!docs.contains("RUX16_RUSTC"));
    assert!(!docs.contains("RUX16_LLVM_BIN_DIR"));
    assert!(!docs.contains(".k16e"));

    let bootstrap_docs = fs::read_to_string(&bootstrap_docs).expect("Rust bootstrap docs exist");
    assert!(bootstrap_docs.contains("tools/k16-rustc-bootstrap-probe.sh"));
    assert!(bootstrap_docs.contains("tools/k16-rust-nocore-smoke.sh"));
    assert!(bootstrap_docs.contains("tools/k16-unknown-kraftos.json"));
    assert!(bootstrap_docs.contains("K16_RUSTC"));
    assert!(bootstrap_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(bootstrap_docs.contains("build-rux-min/bin/llvm-config"));
    assert!(bootstrap_docs.contains("k16"));
    assert!(!bootstrap_docs.contains("tools/rux16-rustc-bootstrap-probe.sh"));
    assert!(!bootstrap_docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(!bootstrap_docs.contains("RUX16_RUSTC"));
    assert!(!bootstrap_docs.contains("RUX16_LLVM_BIN_DIR"));

    let feasibility_docs =
        fs::read_to_string(&feasibility_docs).expect("Rust feasibility docs exist");
    let strategy_docs = fs::read_to_string(&strategy_docs).expect("Rust strategy docs exist");
    for docs in [&feasibility_docs, &strategy_docs] {
        assert!(docs.contains("k16 link"));
        assert!(!docs.contains("rux link"));
        assert!(!docs.contains("rux run"));
        assert!(!docs.contains("rux runtime"));
    }
    assert!(feasibility_docs.contains("k16 runtime k16-startup"));
    assert!(feasibility_docs.contains("k16 run"));
}

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("native/k16-tools has repo root grandparent")
}
