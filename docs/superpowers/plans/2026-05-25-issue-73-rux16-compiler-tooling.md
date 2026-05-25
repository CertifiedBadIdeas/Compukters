# Rux16 Compiler And Volume Tooling Implementation Plan

> Issue: [#73](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/73)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the compiler/tooling surface from LowImage/RUXI to explicit Rux16 binary artifacts and ruxvol storage tooling.

**Architecture:** Keep `native/rux-compiler` as the source-language frontend owner, but replace the old public `emit/disasm/run` model with one `rux` CLI that exposes `compile`, `disasm`, and `volume` subcommands. Store boot artifacts through ruxvol commands instead of making the compiler mutate storage images directly.

**Tech Stack:** Rust 2021, Cargo integration tests, shell wrapper `rux`, Rux16 VM instruction encoding/decoding.

---

### Task 1: Add Minimal Ruxvol Boot-Slot Library And CLI

**Files:**
- Create: `native/rux-compiler/src/volume.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Create: `native/rux-compiler/src/bin/rux.rs`
- Modify: `rux`
- Test: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Write failing CLI tests**

Create `native/rux-compiler/tests/rux_volume_cli.rs` with tests that:

```rust
use std::fs;
use std::process::Command;

#[test]
fn rux_volume_create_writes_empty_ruxvol_header() {
    let path = temp_file("create-storage0.ruxvol");
    let output = Command::new(env!("CARGO_BIN_EXE_rux"))
        .args(["volume", "create", path.to_str().unwrap(), "--size", "4096"])
        .output()
        .expect("rux runs");

    assert!(output.status.success(), "stderr: {}", String::from_utf8_lossy(&output.stderr));
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..8], b"RUXVOL1\0");
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 4096);
    assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 0);
    assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 0);
}

#[test]
fn rux_volume_put_boot_records_boot_artifact() {
    let volume_path = temp_file("boot-storage0.ruxvol");
    let boot_path = temp_file("boot.bin");
    fs::write(&boot_path, [0x01, 0x02, 0x03, 0x04]).expect("boot writes");

    assert!(Command::new(env!("CARGO_BIN_EXE_rux"))
        .args(["volume", "create", volume_path.to_str().unwrap(), "--size", "4096"])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(env!("CARGO_BIN_EXE_rux"))
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-boot runs");

    assert!(output.status.success(), "stderr: {}", String::from_utf8_lossy(&output.stderr));
    let bytes = fs::read(&volume_path).expect("volume reads");
    let offset = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
    let size = u32::from_le_bytes(bytes[16..20].try_into().unwrap()) as usize;
    let checksum = u32::from_le_bytes(bytes[20..24].try_into().unwrap());
    assert_eq!(size, 4);
    assert_eq!(checksum, 10);
    assert_eq!(&bytes[offset..offset + size], &[0x01, 0x02, 0x03, 0x04]);
}

fn temp_file(name: &str) -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!(
        "rux-volume-cli-{}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cargo test --test rux_volume_cli --manifest-path native/rux-compiler/Cargo.toml
```

Expected: fail because the `rux` binary or `volume` subcommand is not implemented.

- [ ] **Step 3: Implement minimal ruxvol library**

Add `native/rux-compiler/src/volume.rs` with:

```rust
pub const RUXVOL_MAGIC: &[u8; 8] = b"RUXVOL1\0";
pub const RUXVOL_HEADER_SIZE: usize = 24;
pub const RUXVOL_BOOT_OFFSET: usize = 512;

pub fn create_empty_volume(size: usize) -> Result<Vec<u8>, String>;
pub fn put_boot(volume: &mut [u8], boot: &[u8]) -> Result<(), String>;
```

The implementation should write little-endian header fields and use an additive `u32` checksum over boot bytes.

- [ ] **Step 4: Implement `rux volume` CLI**

Create `native/rux-compiler/src/bin/rux.rs` with:

```text
rux volume create <volume.ruxvol> --size <bytes>
rux volume put-boot <volume.ruxvol> <boot.bin>
```

Update the root `rux` wrapper to run the new `rux` binary for all commands.

- [ ] **Step 5: Run GREEN and commit**

Run:

```bash
cargo test --test rux_volume_cli --manifest-path native/rux-compiler/Cargo.toml
git add rux native/rux-compiler/src/lib.rs native/rux-compiler/src/volume.rs native/rux-compiler/src/bin/rux.rs native/rux-compiler/tests/rux_volume_cli.rs
git commit -m "feat(tooling): add ruxvol boot slot commands"
```

### Task 2: Add Rux16 Artifact Disassembly Command

**Files:**
- Create: `native/rux-compiler/src/rux16_disasm.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Modify: `native/rux-compiler/src/bin/rux.rs`
- Test: `native/rux-compiler/tests/rux_disasm_rux16_cli.rs`

- [ ] **Step 1: Write failing disasm test**

Create a test that writes binary Rux16 words to `bios.flash`, runs:

```bash
rux disasm --target bios bios.flash
```

and expects stdout to include addresses starting at the BIOS flash base plus decoded instruction names such as `const4`, `store32`, and `halt`.

- [ ] **Step 2: Run RED**

Run:

```bash
cargo test --test rux_disasm_rux16_cli --manifest-path native/rux-compiler/Cargo.toml
```

Expected: fail because `rux disasm --target bios` is not implemented.

- [ ] **Step 3: Implement Rux16 disassembly**

Decode raw little-endian words with target-specific base addresses. Require `--target`; do not infer from file names or magic values.

- [ ] **Step 4: Run GREEN and commit**

Run:

```bash
cargo test --test rux_disasm_rux16_cli --manifest-path native/rux-compiler/Cargo.toml
git add native/rux-compiler/src/lib.rs native/rux-compiler/src/rux16_disasm.rs native/rux-compiler/src/bin/rux.rs native/rux-compiler/tests/rux_disasm_rux16_cli.rs
git commit -m "feat(tooling): disassemble Rux16 binary artifacts"
```

### Task 3: Add Rux16 Compile Target Skeleton

**Files:**
- Create: `native/rux-compiler/src/artifact.rs`
- Modify: `native/rux-compiler/src/backend/mod.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Modify: `native/rux-compiler/src/bin/rux.rs`
- Test: `native/rux-compiler/tests/rux_compile_cli.rs`

- [ ] **Step 1: Write failing compile-target test**

Create a minimal source file and assert:

```bash
rux compile --target bios input.rx -o bios.flash
```

writes binary bytes and does not write a `RUXI` header.

- [ ] **Step 2: Run RED**

Run:

```bash
cargo test --test rux_compile_cli --manifest-path native/rux-compiler/Cargo.toml
```

Expected: fail because `rux compile --target bios` is not implemented.

- [ ] **Step 3: Implement the first minimal Rux16 backend path**

Add a Rux16 artifact type and backend entrypoint. Keep the scope intentionally small; unsupported language features should return explicit compile errors instead of lowering through LowImage.

- [ ] **Step 4: Run GREEN and commit**

Run:

```bash
cargo test --test rux_compile_cli --manifest-path native/rux-compiler/Cargo.toml
git add native/rux-compiler/src/artifact.rs native/rux-compiler/src/backend/mod.rs native/rux-compiler/src/lib.rs native/rux-compiler/src/bin/rux.rs native/rux-compiler/tests/rux_compile_cli.rs
git commit -m "feat(compiler): add Rux16 BIOS compile target"
```

### Task 4: Retire Old Public LowImage CLI Surface

**Files:**
- Modify: `native/rux-compiler/Cargo.toml`
- Delete or deprecate internally: `native/rux-compiler/src/bin/rux-emit.rs`
- Delete or deprecate internally: `native/rux-compiler/src/bin/rux-disasm.rs`
- Delete or deprecate internally: `native/rux-compiler/src/bin/rux-run.rs`
- Modify: `rux`
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Add guard test**

Add a test that `rux emit` exits with usage and that `rux compile --target bios` is the documented path.

- [ ] **Step 2: Remove or hide old binaries from the public surface**

Stop exposing `rux-emit`, `rux-disasm`, and `rux-run` as active user-facing commands. Do not add aliases that silently preserve old RUXI behavior.

- [ ] **Step 3: Update active docs**

Replace `.ruxi` and LowImage compiler references in active docs with `bios.flash`, Rux16 artifacts, and ruxvol boot tooling.

- [ ] **Step 4: Run final verification and commit**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml
cargo test --manifest-path native/rux-vm/Cargo.toml
./gradlew-sandbox :native-runtime:test
git diff --check
git commit -m "refactor(tooling): retire public RUXI CLI path"
```
