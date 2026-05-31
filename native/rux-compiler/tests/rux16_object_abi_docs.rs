use std::fs;
use std::path::Path;

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("compiler crate lives under native/rux-compiler")
}

fn normalized_doc(path: &str) -> String {
    fs::read_to_string(repo_root().join(path))
        .expect("ABI doc reads")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn rux16_object_abi_docs_define_elf_relocatable_contract() {
    let docs = normalized_doc("docs/abi/rux16-object-v1.md");

    for required in [
        "Rux16 relocatable objects use ELF32 little-endian `ET_REL` files",
        "`e_machine = 0x5258`",
        "LLVM must emit relocatable objects, not `RUXE`",
        "The VM must not parse ELF",
        "Unsupported relocations are link-time errors",
        "SHF_ALLOC",
        "SHT_NOBITS",
        ".text.rux16",
        ".rodata",
        ".data",
        ".bss",
        ".rux16.attributes",
        "`STT_FILE`",
        "`SHN_ABS`",
        "R_RUX16_ABS32",
        "R_RUX16_BRANCH4",
        "R_RUX16_CALL32",
    ] {
        assert!(
            docs.contains(required),
            "Rux16 object ABI docs must contain `{required}`"
        );
    }
}

#[test]
fn active_abi_index_lists_rux16_object_contract() {
    let docs = normalized_doc("docs/abi/README.md");

    assert!(
        docs.contains("rux16-object-v1.md"),
        "active ABI index must list rux16-object-v1.md"
    );
}

#[test]
fn rux16_object_abi_docs_define_freestanding_runtime_boundary() {
    let docs = normalized_doc("docs/abi/rux16-object-v1.md");

    for required in [
        "k16 runtime rux16-startup -o <startup.ko>",
        "k16 runtime rux16-memory-helpers -o <helpers.ko>",
        "`_start`",
        "`main`",
        "`__rux16_memcpy`",
        "`__rux16_memset`",
        "`__rux16_memmove`",
        "native/rux-compiler/runtime/rux16_memory_helpers.rs",
        "requires `RUX16_RUSTC`",
        "`RUX16_LLVM_BIN_DIR`",
        "Rust-built helper object",
        "Missing helper symbols are link-time errors",
        "The helper object is not implicit",
        "The startup object writes the low byte of `main`'s `r0` return value to `debug::WRITE`",
    ] {
        assert!(
            docs.contains(required),
            "Rux16 object ABI runtime docs must contain `{required}`"
        );
    }
}

#[test]
fn active_abi_docs_use_k16_for_machine_tooling_commands() {
    let docs = [
        normalized_doc("docs/abi/CHANGELOG.md"),
        normalized_doc("docs/abi/ruxe-v1.md"),
        normalized_doc("docs/abi/rux16-v1.md"),
    ]
    .join(" ");

    for required in [
        "k16 disasm",
        "k16 runtime rux16-startup",
        "k16 runtime rux16-memory-helpers",
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
