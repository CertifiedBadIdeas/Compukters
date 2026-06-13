use std::fs;
use std::path::Path;

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
        .expect("compiler crate lives under rust/host/k16-tools")
}

fn normalized_doc(path: &str) -> String {
    fs::read_to_string(repo_root().join(path))
        .expect("ABI doc reads")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn k16_object_abi_docs_define_elf_relocatable_contract() {
    let docs = normalized_doc("docs/abi/k16-object-v1.md");

    for required in [
        "K16 relocatable objects use ELF32 little-endian `ET_REL` files",
        "`e_machine = 0x5258`",
        "LLVM must emit relocatable objects, not `K16E`",
        "The VM must not parse ELF",
        "Unsupported relocations are link-time errors",
        "SHF_ALLOC",
        "SHT_NOBITS",
        ".text.k16",
        ".rodata",
        ".data",
        ".bss",
        ".k16.attributes",
        "`STT_FILE`",
        "`SHN_ABS`",
        "R_K16_ABS32",
        "R_K16_BRANCH4",
        "R_K16_CALL32",
    ] {
        assert!(
            docs.contains(required),
            "K16 object ABI docs must contain `{required}`"
        );
    }
}

#[test]
fn active_abi_index_lists_k16_object_contract() {
    let docs = normalized_doc("docs/abi/README.md");

    assert!(
        docs.contains("k16-object-v1.md"),
        "active ABI index must list k16-object-v1.md"
    );
}

#[test]
fn k16_object_abi_docs_define_freestanding_runtime_boundary() {
    let docs = normalized_doc("docs/abi/k16-object-v1.md");

    for required in [
        "k16 runtime k16-startup [--target <program|program-init|program-child>] -o <startup.ko>",
        "k16 runtime k16-memory-helpers -o <helpers.ko>",
        "k16 runtime k16-cpu-helpers -o <cpu-helpers.ko>",
        "`_start`",
        "`main`",
        "`__k16_memcpy`",
        "`__k16_memset`",
        "`__k16_memmove`",
        "`__k16_wait_once`",
        "`__k16_yield_once`",
        "`__k16_write_trap_vector`",
        "`__k16_iret_once`",
        "`__k16_syscall_once`",
        "`__k16_syscall0`",
        "`__k16_syscall1`",
        "`__k16_read_trap_arg0`",
        "`__k16_read_trap_arg1`",
        "`__k16_read_trap_arg2`",
        "`__k16_syscall3`",
        "`__k16_iret_with_r0`",
        "rust/guest/k16-rt/src/no_core_helpers.rs",
        "requires `K16_RUSTC`",
        "`K16_LLVM_BIN_DIR`",
        "Rust-built helper object",
        "Missing helper symbols are link-time errors",
        "The helper object is not implicit",
        "passes the returned `r0` value to the K16 `EXIT` syscall as the process status",
        "`program-init` uses stack top `0x0000c000`",
        "`program-child` use stack top `0x00010000`",
    ] {
        assert!(
            docs.contains(required),
            "K16 object ABI runtime docs must contain `{required}`"
        );
    }
}

#[test]
fn active_abi_docs_use_k16_for_machine_tooling_commands() {
    let docs = [
        normalized_doc("docs/abi/CHANGELOG.md"),
        normalized_doc("docs/abi/k16e-v1.md"),
        normalized_doc("docs/abi/k16-cpu-v1.md"),
    ]
    .join(" ");

    for required in [
        "k16 disasm",
        "k16 runtime k16-startup",
        "k16 runtime k16-memory-helpers",
        "k16 runtime k16-cpu-helpers",
        "k16 link",
        "K16 tooling",
    ] {
        assert!(
            docs.contains(required),
            "active ABI docs must contain `{required}`"
        );
    }

    for retired in ["rux disasm", "rux runtime", "rux link", "rux run"] {
        assert!(
            !docs.contains(retired),
            "active ABI docs must not contain `{retired}`"
        );
    }
}
