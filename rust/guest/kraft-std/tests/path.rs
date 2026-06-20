#![cfg(feature = "host-test")]

use kraft_std::path::{PathBuffer, WorkingDirectory, MAX_PATH_BYTES};

#[test]
fn working_directory_starts_at_root() {
    let cwd = WorkingDirectory::new();

    assert_eq!(cwd.as_bytes(), b"/");
}

#[test]
fn path_buffer_resolves_absolute_path_components() {
    let cwd = WorkingDirectory::new();
    let mut path = PathBuffer::new();

    cwd.resolve_into(b"/bin/../etc//motd", &mut path).unwrap();

    assert_eq!(path.as_bytes(), b"/etc/motd");
}

#[test]
fn path_buffer_resolves_relative_path_from_cwd() {
    let mut cwd = WorkingDirectory::new();
    let mut path = PathBuffer::new();
    cwd.resolve_into(b"etc", &mut path).unwrap();
    cwd.set_from_resolved(&path).unwrap();

    cwd.resolve_into(b"../bin/./ls.kx", &mut path).unwrap();

    assert_eq!(path.as_bytes(), b"/bin/ls.kx");
}

#[test]
fn path_buffer_keeps_parent_of_root_at_root() {
    let cwd = WorkingDirectory::new();
    let mut path = PathBuffer::new();

    cwd.resolve_into(b"../../", &mut path).unwrap();

    assert_eq!(path.as_bytes(), b"/");
}

#[test]
fn path_buffer_rejects_overlong_paths() {
    let cwd = WorkingDirectory::new();
    let mut path = PathBuffer::new();
    let mut input = [b'a'; MAX_PATH_BYTES + 1];
    input[0] = b'/';

    assert!(cwd.resolve_into(&input, &mut path).is_err());
}

#[test]
fn path_buffer_replaces_with_program_path_parts() {
    let mut path = PathBuffer::new();

    path.replace_with_parts(b"/bin/", b"uname", b".kx").unwrap();

    assert_eq!(path.as_bytes(), b"/bin/uname.kx");
}
