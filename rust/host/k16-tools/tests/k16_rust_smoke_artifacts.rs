use std::fs;
use std::path::Path;

#[test]
fn k16_guest_interrupt_smoke_artifacts_are_documented() {
    let root = repo_root();
    let smoke_script = root.join("tools/k16-guest-interrupt-smoke.sh");
    let docs = root.join("docs/toolchains/k16-guest-interrupt-smoke.md");

    let script = fs::read_to_string(&smoke_script).expect("guest interrupt smoke script exists");
    assert!(script.contains("K16_CARGO"));
    assert!(script.contains("K16_RUSTC"));
    assert!(script.contains("K16_TOOL"));
    assert!(script.contains("k16-rt"));
    assert!(script.contains("install_trap_vector"));
    assert!(script.contains("set_interrupt_mask"));
    assert!(script.contains("enable_interrupts"));
    assert!(script.contains("trap_cause"));
    assert!(script.contains("trap_pc"));
    assert!(script.contains("trap_value"));
    assert!(script.contains("interrupt_pending"));
    assert!(script.contains("iret_once"));
    assert!(script.contains("advance_game_tick"));
    assert!(script.contains("debug_bytes=492a"));
    assert!(!script.contains("RUX16"));

    let docs = fs::read_to_string(&docs).expect("guest interrupt smoke docs exist");
    assert!(docs.contains("tools/k16-guest-interrupt-smoke.sh"));
    assert!(docs.contains("timer0"));
    assert!(docs.contains("k16-rt"));
    assert!(docs.contains("advance_game_tick"));
    assert!(docs.contains("debug_bytes=492a"));
    assert!(!docs.contains("RUX16"));
}

#[test]
fn k16_kernel_timer_smoke_artifacts_are_documented() {
    let root = repo_root();
    let smoke_script = root.join("tools/k16-kernel-timer-smoke.sh");
    let docs = root.join("docs/toolchains/k16-kernel-timer-smoke.md");

    let script = fs::read_to_string(&smoke_script).expect("kernel timer smoke script exists");
    assert!(script.contains("rust/guest/k16-kernel/Cargo.toml"));
    assert!(script.contains("--k16-target=kernel"));
    assert!(script.contains("k16-cpu-helpers"));
    assert!(script.contains("decode_k16_executable"));
    assert!(script.contains("K16eAbiKind::Kernel"));
    assert!(script.contains("boot_handoff_k16_from_guest_ram"));
    assert!(script.contains("advance_game_tick"));
    assert!(script.contains("syscall"));
    assert!(script.contains("signal=yield"));
    assert!(script.contains("debug_suffix=7c7c5321"));
    assert!(script.contains("continuation_r2=83"));
    assert!(script.contains("continuation_r3=0"));
    assert!(!script.contains("intc0"));
    assert!(!script.contains("RUX16"));

    let docs = fs::read_to_string(&docs).expect("kernel timer smoke docs exist");
    assert!(docs.contains("tools/k16-kernel-timer-smoke.sh"));
    assert!(docs.contains("rust/guest/k16-kernel"));
    assert!(docs.contains("k16-cpu-helpers"));
    assert!(docs.contains("timer0"));
    assert!(docs.contains("syscall"));
    assert!(docs.contains("READY"));
    assert!(docs.contains("debug_suffix=7c7c5321"));
    assert!(docs.contains("continuation_r2=83"));
    assert!(docs.contains("continuation_r3=0"));
    assert!(!docs.contains("intc0"));
    assert!(!docs.contains("RUX16"));
}

#[test]
fn rust_nocore_smoke_artifacts_are_documented_and_strict() {
    let root = repo_root();
    let target_spec = root.join("tools/k16-unknown-kraftos.json");
    let llvm_smoke_script = root.join("tools/k16-llvm-smoke.sh");
    let clang_smoke_script = root.join("tools/k16-clang-smoke.sh");
    let smoke_script = root.join("tools/k16-rust-nocore-smoke.sh");
    let core_smoke_script = root.join("tools/k16-rust-core-smoke.sh");
    let bootstrap_probe = root.join("tools/k16-rustc-bootstrap-probe.sh");
    let runtime_helpers = root.join("rust/guest/k16-rt/src/no_core_helpers.rs");
    let retired_host_runtime = root.join("rust/host/k16-tools/runtime");
    let llvm_docs = root.join("docs/toolchains/k16-llvm-smoke.md");
    let clang_docs = root.join("docs/toolchains/k16-clang-smoke.md");
    let docs = root.join("docs/toolchains/k16-rust-nocore-smoke.md");
    let core_docs = root.join("docs/toolchains/k16-rust-core-smoke.md");
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
    assert!(spec.contains("\"executables\": true"));
    assert!(!spec.contains("\"executables\": false"));

    let llvm_smoke = fs::read_to_string(&llvm_smoke_script).expect("LLVM smoke script exists");
    assert!(llvm_smoke.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(llvm_smoke.contains(".toolchain/build/cargo/k16-tools"));
    assert!(llvm_smoke.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(llvm_smoke.contains("K16_CARGO_MANIFEST"));
    assert!(!llvm_smoke.contains("RUX_CARGO_MANIFEST"));
    assert!(!llvm_smoke.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin"));
    assert!(llvm_smoke.contains("--bin k16"));
    assert!(llvm_smoke.contains("main.kx"));
    assert!(llvm_smoke.contains("call-helper.kx"));
    assert!(llvm_smoke.contains("stack-local-main.kx"));
    assert!(!llvm_smoke.contains("--bin rux"));
    assert!(!llvm_smoke.contains(".k16e"));

    let clang_smoke = fs::read_to_string(&clang_smoke_script).expect("Clang smoke script exists");
    assert!(clang_smoke.contains(".toolchain/build/llvm/k16/bin"));
    assert!(clang_smoke.contains(".toolchain/build/cargo/k16-tools"));
    assert!(clang_smoke.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(clang_smoke.contains("K16_CARGO_MANIFEST"));
    assert!(!clang_smoke.contains("RUX_CARGO_MANIFEST"));
    assert!(!clang_smoke.contains("toolchains/Compukter-Kraft-llvm/build-k16/bin"));
    assert!(clang_smoke.contains("--bin k16"));
    assert!(clang_smoke.contains("main.kx"));
    assert!(!clang_smoke.contains("--bin rux"));
    assert!(!clang_smoke.contains(".k16e"));

    let script = fs::read_to_string(&smoke_script).expect("Rust no_core smoke script exists");
    assert!(script.contains("K16_RUSTC"));
    assert!(script.contains("K16_LLVM_BIN_DIR"));
    assert!(script.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(script.contains(".toolchain/build/cargo/k16-tools"));
    assert!(script.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(script.contains("K16_CARGO_MANIFEST"));
    assert!(!script.contains("RUX_CARGO_MANIFEST"));
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

    let core_script =
        fs::read_to_string(&core_smoke_script).expect("Rust core smoke script exists");
    assert!(core_script.contains("-Z build-std=core"));
    assert!(core_script.contains("-Z json-target-spec"));
    assert!(core_script.contains("[lib]"));
    assert!(core_script.contains("--lib"));
    assert!(core_script.contains("RUSTFLAGS=\"-Cjump-tables=no -Cdebuginfo=0\""));
    assert!(core_script.contains("-Cjump-tables=no"));
    assert!(core_script.contains("-Cdebuginfo=0"));
    assert!(core_script.contains("#![no_std]"));
    assert!(!core_script.contains("[[bin]]"));
    assert!(!core_script.contains("#![no_main]"));
    assert!(core_script.contains("core::hint::spin_loop"));
    assert!(core_script.contains("K16_RUSTC"));
    assert!(core_script.contains("K16_LLVM_BIN_DIR"));
    assert!(core_script.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(core_script.contains(".toolchain/build/cargo/k16-tools"));
    assert!(core_script.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(core_script.contains("K16_RUST_TARGET_JSON"));
    assert!(core_script.contains("tools/k16-unknown-kraftos.json"));
    assert!(core_script.contains("k16-memory-helpers"));
    assert!(core_script.contains("K16 Rust core build failed."));
    assert!(core_script.contains("cargo-rustc.stderr"));
    assert!(core_script.contains("main.kx"));
    assert!(core_script.contains("debug_bytes=2a"));
    assert!(core_script.contains("--bin k16"));
    assert!(!core_script.contains("#![no_core]"));
    assert!(!core_script.contains("extern crate alloc"));
    assert!(!core_script.contains("|| true"));
    assert!(!core_script.contains("RUX16_RUSTC"));
    assert!(!core_script.contains("RUX16_LLVM_BIN_DIR"));

    assert!(
        !retired_host_runtime.exists(),
        "K16 host tools must not own guest runtime helper source"
    );
    let helpers =
        fs::read_to_string(&runtime_helpers).expect("K16 guest runtime helper source exists");
    assert!(helpers.contains("#![no_core]"));
    assert!(helpers.contains("#![no_main]"));
    assert!(helpers.contains("__k16_memcpy"));
    assert!(helpers.contains("__k16_memset"));
    assert!(helpers.contains("__k16_memmove"));
    assert!(helpers.contains("__divdi3"));
    assert!(helpers.contains("__udivdi3"));
    assert!(helpers.contains("__moddi3"));
    assert!(helpers.contains("__umoddi3"));
    assert!(helpers.contains("__ashldi3"));
    assert!(helpers.contains("__lshrdi3"));
    assert!(helpers.contains("__ashrdi3"));
    assert!(!helpers.contains("extern crate std"));

    let probe = fs::read_to_string(&bootstrap_probe).expect("Rust bootstrap probe script exists");
    assert!(probe.contains("K16_RUST_SRC"));
    assert!(probe.contains("K16_LLVM_CONFIG"));
    assert!(probe.contains("K16_RUST_BUILD_DIR"));
    assert!(probe.contains("K16_RUST_HOST"));
    assert!(probe.contains("toolchains/Compukter-Kraft-rust"));
    assert!(probe.contains(".toolchain/build/llvm/k16-min/bin/llvm-config"));
    assert!(probe.contains(".toolchain/build/rust/k16"));
    assert!(!probe.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config"));
    assert!(!probe.contains("$RUST_SRC/build/k16"));
    assert!(probe.contains("REQUIRED_LLVM_TOOLS"));
    assert!(probe.contains("llvm-cov"));
    assert!(probe.contains("llvm-nm"));
    assert!(probe.contains("llvm-objcopy"));
    assert!(probe.contains("llvm-profdata"));
    assert!(probe.contains("--targets-built"));
    assert!(probe.contains("--obj-root"));
    assert!(probe.contains("CMakeCache.txt"));
    assert!(probe.contains("LLVM_SOURCE_DIR:STATIC="));
    assert!(probe.contains("src/llvm-project"));
    assert!(probe.contains("ls-tree HEAD src/llvm-project"));
    assert!(probe.contains("merge-base --is-ancestor"));
    assert!(probe.contains("Rust-pinned LLVM commit"));
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
    assert!(llvm_docs.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(!llvm_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin"));
    assert!(!llvm_docs.contains("tools/rux16-llvm-smoke.sh"));
    assert!(!llvm_docs.contains("RUX16_LLVM_BIN_DIR"));

    let clang_docs = fs::read_to_string(&clang_docs).expect("Clang smoke docs exist");
    assert!(clang_docs.contains("tools/k16-clang-smoke.sh"));
    assert!(clang_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(clang_docs.contains(".toolchain/build/llvm/k16/bin"));
    assert!(!clang_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16/bin"));
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

    let core_docs = fs::read_to_string(&core_docs).expect("Rust core smoke docs exist");
    assert!(core_docs.contains("tools/k16-rust-core-smoke.sh"));
    assert!(core_docs.contains("-Z build-std=core"));
    assert!(core_docs.contains("core only"));
    assert!(core_docs.contains("library crate with an exported C ABI `main`"));
    assert!(core_docs.contains("-Cdebuginfo=0"));
    assert!(core_docs.contains("no alloc"));
    assert!(core_docs.contains("no std"));
    assert!(core_docs.contains("K16_RUSTC"));
    assert!(core_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(core_docs.contains("debug_bytes=2a"));
    assert!(!core_docs.contains("tools/rux16-rust-core-smoke.sh"));
    assert!(!core_docs.contains("RUX16_RUSTC"));

    let bootstrap_docs = fs::read_to_string(&bootstrap_docs).expect("Rust bootstrap docs exist");
    assert!(bootstrap_docs.contains("tools/k16-rustc-bootstrap-probe.sh"));
    assert!(bootstrap_docs.contains("tools/k16-rust-nocore-smoke.sh"));
    assert!(bootstrap_docs.contains("tools/k16-unknown-kraftos.json"));
    assert!(bootstrap_docs.contains("K16_RUSTC"));
    assert!(bootstrap_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(bootstrap_docs.contains(".toolchain/build/llvm/k16-min/bin/llvm-config"));
    assert!(bootstrap_docs.contains(".toolchain/build/rust/k16"));
    assert!(
        !bootstrap_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config")
    );
    assert!(!bootstrap_docs.contains("toolchains/Compukter-Kraft-rust/build/k16"));
    assert!(bootstrap_docs.contains("Rust-pinned LLVM commit"));
    assert!(bootstrap_docs.contains("merge-base --is-ancestor"));
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

#[test]
fn llvm_and_rust_backend_sources_use_k16_without_retired_rux16_names() {
    let root = repo_root();
    let backend_paths = [
        "toolchains/Compukter-Kraft-llvm/llvm/include/llvm/TargetParser/Triple.h",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/TargetParser/Triple.cpp",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/CMakeLists.txt",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/TargetInfo/K16TargetInfo.cpp",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/K16TargetMachine.cpp",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_llvm/build.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_llvm/src/lib.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/mod.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/k16.rs",
    ];

    for path in backend_paths {
        let contents = fs::read_to_string(root.join(path))
            .unwrap_or_else(|error| panic!("expected active K16 backend file `{path}`: {error}"));
        assert!(
            !contents.contains("rux16")
                && !contents.contains("Rux16")
                && !contents.contains("RUX16"),
            "active K16 backend file `{path}` should not keep retired Rux16 names"
        );
    }

    let retired_paths = [
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/Rux16",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/rux16.rs",
    ];
    for path in retired_paths {
        assert!(
            !root.join(path).exists(),
            "retired backend path `{path}` should not exist"
        );
    }
}

#[test]
fn active_k16_tools_do_not_ship_rux_compiler_surface() {
    let root = repo_root();
    let retirement_audit =
        fs::read_to_string(root.join("docs/toolchains/rux-language-retirement-audit.md"))
            .expect("Rux language retirement audit exists");

    for path in [
        "rux",
        "rust/host/k16-tools/src/bin/rux.rs",
        "rust/host/k16-tools/src/advice.rs",
        "rust/host/k16-tools/src/frontend",
        "rust/host/k16-tools/src/k16_asm.rs",
        "rust/host/k16-tools/src/runtime",
        "rust/host/k16-tools/stdlib",
        "rust/host/k16-tools/examples",
        "rust/host/k16-tools/tests/k16_artifact_backend.rs",
        "rust/host/k16-tools/tests/rux_check_cli.rs",
        "rust/host/k16-tools/tests/rux_compile_cli.rs",
        "rust/host/k16-tools/tests/rux_compiler_runtime_surface.rs",
        "rust/host/k16-tools/tests/rux_public_cli_surface.rs",
    ] {
        assert!(
            !root.join(path).exists(),
            "active K16 tools must not ship retired Rux compiler path `{path}`"
        );
    }

    assert!(retirement_audit.contains(
        "[#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):\n  Rux stdlib and source advice have been removed from active tooling."
    ));
    assert!(!retirement_audit.contains(
        "[#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):\n  Remove Rux stdlib and source advice."
    ));
}

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
        .expect("rust/host/k16-tools has repo root great-grandparent")
}
