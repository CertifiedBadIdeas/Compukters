#![cfg(feature = "host-test")]

use kraft_std::coreutils;

#[test]
fn path_arg_policy_resolves_all_filesystem_utility_args() {
    let args = [b"workflow.moved".as_slice(), b"workflow.txt".as_slice()];

    assert!(coreutils::should_resolve_path_arg(b"cat", &args, 0));
    assert!(coreutils::should_resolve_path_arg(b"ls", &args, 1));
    assert!(coreutils::should_resolve_path_arg(b"stat", &args[..1], 0));
    assert!(coreutils::should_resolve_path_arg(b"rm", &args, 0));
    assert!(coreutils::should_resolve_path_arg(b"mkdir", &args, 1));
    assert!(coreutils::should_resolve_path_arg(b"rmdir", &args, 0));
    assert!(coreutils::should_resolve_path_arg(b"cp", &args, 0));
    assert!(coreutils::should_resolve_path_arg(b"mv", &args, 1));
}

#[test]
fn path_arg_policy_resolves_only_write_path_argument() {
    let append_args = [
        b"--append".as_slice(),
        b"workflow.txt".as_slice(),
        b"-beta".as_slice(),
    ];
    let overwrite_args = [b"workflow.txt".as_slice(), b"-beta".as_slice()];

    assert!(!coreutils::should_resolve_path_arg(
        b"write",
        &append_args,
        0
    ));
    assert!(coreutils::should_resolve_path_arg(
        b"write",
        &append_args,
        1
    ));
    assert!(!coreutils::should_resolve_path_arg(
        b"write",
        &append_args,
        2
    ));
    assert!(coreutils::should_resolve_path_arg(
        b"write",
        &overwrite_args,
        0
    ));
    assert!(!coreutils::should_resolve_path_arg(
        b"write",
        &overwrite_args,
        1
    ));
}

#[test]
fn path_arg_policy_leaves_non_filesystem_commands_unresolved() {
    let args = [b"payload".as_slice()];

    assert!(!coreutils::should_resolve_path_arg(b"echo", &args, 0));
    assert!(!coreutils::should_resolve_path_arg(b"uname", &args, 0));
    assert!(!coreutils::should_resolve_path_arg(b"alloc", &args, 0));
    assert!(!coreutils::should_resolve_path_arg(b"unknown", &args, 0));
}
