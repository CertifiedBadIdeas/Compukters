use std::fs;
use std::path::Path;

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("compiler crate lives under host/k16-tools")
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
        "k16 link --target <bios|boot|kernel|program|program-dynamic|shared-object>",
        "`program-dynamic` target emits a K16E v2 dynamic user program",
        "`--import <library>:<symbol>` records",
        "`--dylib <library.kso>` inputs",
        "The `shared-object` target emits a K16E v4 shared object",
        "With `--shareable`, the `shared-object` target emits K16E v7",
        "may rewrite canonical `const32 symbol`",
        "A readonly/text relocation that cannot be materialized this way is a link-time error",
        "must not silently emit legacy v4 as a fallback",
        "Dynamic program targets may use split executable/read-only and writable load sections",
        "writable/non-executable under the MMU",
        "`--import <library>:<symbol>` has the same explicit import metadata meaning in both entry points",
        "`--dylib` also has the same meaning in both entry points",
        "Symbols declared with `--import` or `--dylib` are not archive-selection roots",
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
fn shared_cpu_helper_runtime_spec_defines_narrow_runtime_contract() {
    let docs =
        normalized_doc(".agents/tmp/specs/2026-06-21/issue-346-k16-shared-cpu-helper-runtime.md");

    for required in [
        "K16 Shared CPU Helper Runtime",
        "Issue: [#346](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/346)",
        "`k16-cpu-helpers.o .text.k16`: 16,632 retained bytes across 12 bundled production userland programs",
        "This is not a dynamic linker",
        "no arbitrary shared objects",
        "no shared writable data",
        "must not silently fall back at runtime",
        "mapped by the kernel into translated user address spaces",
        "user-readable and executable, but not user-writable",
        "The mapping address should be kernel-selected, not hard-coded into user programs",
        "The preferred path is a K16E v3 extension",
        "missing runtime artifact: launch fails",
        "Static-helper user programs remain valid",
        "`./gradlew-sandbox-dev :v1_21_1-neoforge:reportK16UserlandSize`",
    ] {
        assert!(
            docs.contains(required),
            "shared CPU helper runtime spec must contain `{required}`"
        );
    }
}

#[test]
fn k16_abi_conformance_matrix_docs_list_supported_backend_contracts() {
    let matrix = normalized_doc("docs/abi/k16-abi-conformance-matrix.md");
    let index = normalized_doc("docs/abi/README.md");
    let toolchain_docs = normalized_doc("docs/toolchains/k16-prebuilt-toolchain.md");

    for required in [
        "K16 ABI Conformance Matrix",
        "scalar arguments and returns",
        "stack-passed arguments",
        "multi-register returns",
        "small aggregate returns",
        "aggregate-by-value arguments",
        "global-address addends",
        "static aggregate field addresses",
        "memory helper libcalls",
        "runtime helper symbols",
        "struct returns are unsupported",
        "varargs are unsupported",
        "dynamic linking is unsupported",
        "call-stack-args.ll",
        "multi-return-registers.ll",
        "aggregate-byvalue.ll",
        "aggregate-static-frame.ll",
        "global-address-offset.ll",
        "mem-intrinsics.ll",
        "k16_runtime_cli",
        "k16_rust_smoke_artifacts",
        ".toolchain/build/llvm/k16-min/bin/llvm-lit toolchains/Compukter-Kraft-llvm/llvm/test/CodeGen/K16",
    ] {
        assert!(
            matrix.contains(required),
            "K16 ABI conformance matrix must contain `{required}`"
        );
    }

    assert!(
        index.contains("k16-abi-conformance-matrix.md"),
        "active ABI index must list k16-abi-conformance-matrix.md"
    );
    assert!(
        toolchain_docs.contains("k16-abi-conformance-matrix.md"),
        "prebuilt toolchain docs must link the K16 ABI conformance matrix"
    );
    assert!(
        toolchain_docs.contains("cargo test --test k16_runtime_cli"),
        "prebuilt toolchain docs must name the runtime CLI smoke command"
    );
    assert!(
        toolchain_docs.contains("cargo test --test k16_rust_smoke_artifacts"),
        "prebuilt toolchain docs must name the Rust smoke artifact command"
    );
}

#[test]
fn k16_object_abi_docs_define_freestanding_runtime_boundary() {
    let docs = normalized_doc("docs/abi/k16-object-v1.md");

    for required in [
        "k16 runtime k16-startup [--target <program|program-dynamic>] -o <startup.ko>",
        "k16 runtime k16-memory-helpers -o <helpers.ko>",
        "k16 runtime k16-cpu-helpers -o <cpu-helpers.ko>",
        "k16 asm <input.kasm> -o <output.ko>",
        "`_start`",
        "`main`",
        "`memcpy`",
        "`memset`",
        "`memmove`",
        "`__k16_memcpy`",
        "`__k16_memset`",
        "`__k16_memmove`",
        "`__k16_wait_once`",
        "`__k16_yield_once`",
        "`__k16_write_trap_vector`",
        "`__k16_iret_once`",
        "`__k16_save_trap_frame`",
        "`__k16_restore_trap_frame`",
        "`__k16_syscall_once`",
        "`__k16_syscall0`",
        "`__k16_syscall1`",
        "`__k16_read_trap_arg0`",
        "`__k16_read_trap_arg1`",
        "`__k16_read_trap_arg2`",
        "`__k16_syscall3`",
        "`__k16_iret_with_r0`",
        "guest/platform/k16/memory-helpers.rs",
        "requires `K16_RUSTC`",
        "`K16_LLVM_BIN_DIR`",
        "`guest/platform/k16/cpu-helpers.kasm`",
        "assembled with `k16 asm`",
        "Rust-built helper object",
        "Missing helper symbols are link-time errors",
        "The helper object is not implicit",
        "passes the returned `r0` value to the K16 `EXIT` syscall as the process status",
        "For `--target program-dynamic`, startup assumes the kernel has already installed the selected process stack top in `r15`",
    ] {
        assert!(
            docs.contains(required),
            "K16 object ABI runtime docs must contain `{required}`"
        );
    }
}

#[test]
fn k16_object_abi_docs_define_shared_cpu_helper_format_boundary() {
    let docs = normalized_doc("docs/abi/k16-object-v1.md");

    for required in [
        "K16E v3",
        "runtime requirement metadata",
        "`--shared-cpu-helpers`",
        "CPU helper runtime ABI version",
        "helper table version",
        "CPU helper relocation records",
        "not a shared-library ABI",
        "not enable dynamic symbol lookup",
        "not load or map the helper runtime artifact",
        "Static-helper programs remain valid",
    ] {
        assert!(
            docs.contains(required),
            "K16 object ABI shared CPU helper format docs must contain `{required}`"
        );
    }
}

#[test]
fn k16e_docs_define_shared_object_v0_boundary() {
    let docs = normalized_doc("docs/abi/k16e-v1.md");

    for required in [
        "K16E v4 shared objects are the first shared-library ABI container",
        "abi_kind 4 (shared-object)",
        "section_count 3",
        "section 1 file_size relocation_count * 8",
        "section 2 kind 5 (exports)",
        "Shared-object relocation records use the same 8-byte record shape",
        "Each export record is 8 bytes",
        "name_offset",
        "K16E shared object v0 does not define imports",
        "applies the shared object's own relocation records",
        "The kernel loader must map executable/read-only shared pages separately from per-process writable data",
        "K16E v7 shareable shared objects split read-only shared payload from private writable data",
        "without a fixed shared virtual window",
        "section_count 4",
        "section 1 kind 8 (writable load, private per-process)",
        "Relocation records in v7 must target the private writable segment",
        "The old K16E v3 CPU helper runtime requirement remains a narrow experiment and is superseded as the main implementation path by this shared object ABI",
    ] {
        assert!(
            docs.contains(required),
            "K16E shared object docs must contain `{required}`"
        );
    }
}

#[test]
fn k16e_docs_define_imported_dynamic_program_v0_boundary() {
    let docs = normalized_doc("docs/abi/k16e-v1.md");

    for required in [
        "K16E v5 imported dynamic programs extend v2 with shared-library dependency metadata",
        "K16E v6 dynamic programs split writable program memory",
        "section 1 kind 8 (writable load)",
        "The writable offset must be non-zero and 4 KiB page-aligned",
        "section 2 kind 6 (needed libraries)",
        "section 3 kind 7 (import relocations)",
        "Each import relocation record is 16 bytes",
        "library_index",
        "symbol_name_offset",
        "Needed library names are UTF-8 and NUL-terminated",
        "resolves each needed library from the `ROOT` KFS partition under `/lib/<needed-library>`",
        "Needed-library names are single KFS path components, not full paths",
        "A loader must reject missing needed libraries, missing exported symbols, unsupported import relocation kinds, out-of-range library indexes, and malformed import string tables",
    ] {
        assert!(
            docs.contains(required),
            "K16E imported dynamic program docs must contain `{required}`"
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

#[test]
fn active_abi_docs_define_dynamic_user_program_loading_contract() {
    let docs = normalized_doc("docs/abi/k16e-v1.md");

    for required in [
        "K16E v2 dynamic user programs",
        "do not carry a fixed physical load address",
        "The kernel loader chooses the load base",
        "section 1 kind 2 (relocations)",
        "1 abs32",
        "2 call32",
    ] {
        assert!(
            docs.contains(required),
            "K16E ABI docs must contain `{required}`"
        );
    }
}

#[test]
fn active_abi_docs_define_cpu_helper_runtime_requirement_extension() {
    let docs = normalized_doc("docs/abi/k16e-v1.md");

    for required in [
        "K16E v3 dynamic user programs",
        "runtime requirement metadata",
        "`k16 link --target program-dynamic --shared-cpu-helpers`",
        "section_count 4",
        "section 2 kind 3 (CPU helper requirement)",
        "section 3 kind 4 (CPU helper relocations)",
        "CPU helper runtime ABI version",
        "helper table version",
        "Each CPU helper relocation record is 12 bytes",
        "not a dynamic linker",
        "not a shared-library ABI",
        "must not fall back to embedded `k16-cpu-helpers.o` text",
    ] {
        assert!(
            docs.contains(required),
            "K16E CPU helper runtime requirement docs must contain `{required}`"
        );
    }
}
