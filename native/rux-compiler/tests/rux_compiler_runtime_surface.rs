use std::fs;
use std::path::Path;

#[test]
fn compiler_source_does_not_expose_legacy_low_image_runtime_path() {
    let src_dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut source = String::new();
    collect_rust_source(&src_dir, &mut source);

    for forbidden in [
        "low_image",
        "LowImage",
        "low_image_runner",
        "run_source",
        "RuxRunReport",
        "pub fn compile(",
    ] {
        assert!(
            !source.contains(forbidden),
            "compiler src still exposes legacy runtime token `{forbidden}`"
        );
    }
}

#[test]
fn compiler_depends_on_k16_vm_without_rux_vm_alias() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let cargo_toml = fs::read_to_string(manifest_dir.join("Cargo.toml")).unwrap();

    assert!(cargo_toml.contains("k16-vm = { path = \"../rux-vm\" }"));
    assert!(!cargo_toml.contains("rux-vm ="));
}

fn collect_rust_source(dir: &Path, output: &mut String) {
    for entry in fs::read_dir(dir).expect("compiler source directory reads") {
        let entry = entry.expect("compiler source entry reads");
        let path = entry.path();
        if path.is_dir() {
            collect_rust_source(&path, output);
        } else if path.extension().is_some_and(|extension| extension == "rs") {
            output.push_str(&fs::read_to_string(path).expect("compiler source file reads"));
            output.push('\n');
        }
    }
}
