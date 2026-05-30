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
