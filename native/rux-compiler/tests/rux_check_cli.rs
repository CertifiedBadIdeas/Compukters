use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_check_reports_nested_if_and_suggests_logical_and() {
    let source_path = temp_file("nested-if.rx");
    fs::write(
        &source_path,
        r#"
fn main() {
    if entry_state == 1u8 {
        if name_len == 4u8 {
            return;
        }
    }
}
"#,
    )
    .expect("source writes");

    let output = Command::new(rux_binary())
        .args(["check", source_path.to_str().unwrap()])
        .output()
        .expect("rux check runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("stdout is UTF-8"),
        format!(
            "{}:3:5: suggestion: nested if can be combined with &&\n  help: if entry_state == 1u8 && name_len == 4u8 {{ ... }}\n",
            source_path.display()
        )
    );
}

#[test]
fn rux_check_does_not_suggest_nested_if_when_outer_if_has_else() {
    let source_path = temp_file("nested-if-else.rx");
    fs::write(
        &source_path,
        r#"
fn main() {
    if entry_state == 1u8 {
        if name_len == 4u8 {
            return;
        }
    } else {
        return;
    }
}
"#,
    )
    .expect("source writes");

    let output = Command::new(rux_binary())
        .args(["check", source_path.to_str().unwrap()])
        .output()
        .expect("rux check runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("stdout is UTF-8"),
        ""
    );
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-check-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}
