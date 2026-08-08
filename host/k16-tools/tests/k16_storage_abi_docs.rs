use std::fs;
use std::path::Path;

#[test]
fn storage_volume_abi_docs_use_k16_cli_and_kraft16_extensions() {
    let docs = normalized_doc("docs/abi/k16-storage-volume-v1.md");

    for required in [
        "k16 volume init storage0.kv --size 1048576",
        "k16 volume inspect storage0.kv",
        "k16 fs kfs format root.kfs --blocks 1791",
        "k16 fs kfs put root.kfs /boot/kernel.kx kernel.kx",
        "k16 volume replace-partition storage0.kv ROOT root.kfs",
        "k16 fs kfs get check-root.kfs /boot/kernel.kx check-kernel.kx",
    ] {
        assert!(
            docs.contains(required),
            "storage volume ABI docs must contain `{required}`"
        );
    }

    for retired in ["rux volume", "rux fs", ".ruxvol", ".ruxfs"] {
        assert!(
            !docs.contains(retired),
            "storage volume ABI docs must not contain `{retired}`"
        );
    }
}

#[test]
fn kfs_abi_docs_use_k16_cli_and_kfs_extension() {
    let docs = normalized_doc("docs/abi/kfs-v1.md");

    for required in [
        "k16 fs kfs format <image.kfs> --blocks <blocks>",
        "k16 fs kfs mkdir <image.kfs> <path>",
        "k16 fs kfs put <image.kfs> <path> <host-input>",
        "k16 fs kfs get <image.kfs> <path> <host-output>",
        "k16 fs kfs rm <image.kfs> <path>",
        "k16 fs kfs ls <image.kfs> <path>",
    ] {
        assert!(
            docs.contains(required),
            "KFS ABI docs must contain `{required}`"
        );
    }

    for retired in ["rux volume", "rux fs", ".ruxvol", ".ruxfs"] {
        assert!(
            !docs.contains(retired),
            "KFS ABI docs must not contain `{retired}`"
        );
    }
}

#[test]
fn storage_abi_docs_define_the_read_only_sdk_mount_without_a_second_format() {
    let storage_docs = normalized_doc("docs/abi/k16-storage-volume-v1.md");
    let kfs_docs = normalized_doc("docs/abi/kfs-v1.md");

    for required in [
        "immutable `storage1`",
        "KraftOS mounts that tree at `/sdk`",
        "on-disk K16VOL, K16PT, and KFS encodings are unchanged",
    ] {
        assert!(
            storage_docs.contains(required),
            "storage volume ABI docs must contain `{required}`"
        );
    }

    for required in [
        "/sdk -> optional storage1 ROOT/KFS, read-only",
        "Open file descriptors retain their volume identity",
        "fail with `ERROR_READ_ONLY` before block I/O",
        "`/sdk/bin/*.kx`",
    ] {
        assert!(
            kfs_docs.contains(required),
            "KFS ABI docs must contain `{required}`"
        );
    }
}

fn normalized_doc(path: &str) -> String {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("host/k16-tools has repo root grandparent");
    fs::read_to_string(root.join(path))
        .expect("doc reads")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}
