use std::path::{Path, PathBuf};
use std::process::Command;

#[test]
fn opcode_metadata_describes_every_instruction() {
    let output = Command::new("jq")
        .arg("-e")
        .arg(
            r#"
            def has_string_array($name): has($name) and (.[$name] | type == "array") and all(.[$name][]; type == "string");
            def has_required_metadata:
                has_string_array("reads")
                and has_string_array("writes")
                and has("width")
                and (.width | type == "string")
                and has("signedness")
                and (.signedness | type == "string")
                and has("result_high_bits")
                and (.result_high_bits | type == "string")
                and has_string_array("trap_conditions");

            (.opcodes | length == 60)
            and all(.opcodes[]; has_required_metadata)
            and any(.opcodes[]; .name == "I32Div" and (.trap_conditions | index("divide_by_zero")))
            and any(.opcodes[]; .name == "Load32" and (.trap_conditions | index("memory_fault")))
            and any(.opcodes[]; .name == "I32Const" and .result_high_bits == "zero_extend_32")
            and any(.opcodes[]; .name == "U64Const" and .width == "u64")
            and any(.opcodes[]; .name == "Jump" and (.writes | index("control_flow")))
            "#,
        )
        .arg(opcode_metadata_path())
        .output()
        .expect("jq is available for ABI metadata validation");

    assert!(
        output.status.success(),
        "opcode metadata is incomplete\nstdout:\n{}\nstderr:\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

fn opcode_metadata_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/abi/rux-low-image-v1-opcodes.json")
}
