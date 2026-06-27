use std::fs;
use std::path::Path;

#[test]
fn k16_guest_interrupt_smoke_artifacts_are_documented() {
    let root = repo_root();
    let smoke_script = root.join("tools/k16-guest-interrupt-smoke.sh");
    let docs = root.join("docs/toolchains/k16-guest-interrupt-smoke.md");

    let script = fs::read_to_string(&smoke_script).expect("guest interrupt smoke script exists");
    assert!(script.contains("K16_CARGO"));
    assert!(script.contains("K16_RUSTC"));
    assert!(script.contains("K16_TOOL"));
    assert!(script.contains("k16-rt"));
    assert!(script.contains("install_trap_vector"));
    assert!(script.contains("set_interrupt_mask"));
    assert!(script.contains("enable_interrupts"));
    assert!(script.contains("trap_cause"));
    assert!(script.contains("trap_pc"));
    assert!(script.contains("trap_value"));
    assert!(script.contains("interrupt_pending"));
    assert!(script.contains("iret_once"));
    assert!(script.contains("advance_game_tick"));
    assert!(script.contains("debug_bytes=492a"));
    assert!(!script.contains("RUX16"));

    let docs = fs::read_to_string(&docs).expect("guest interrupt smoke docs exist");
    assert!(docs.contains("tools/k16-guest-interrupt-smoke.sh"));
    assert!(docs.contains("timer0"));
    assert!(docs.contains("k16-rt"));
    assert!(docs.contains("advance_game_tick"));
    assert!(docs.contains("debug_bytes=492a"));
    assert!(!docs.contains("RUX16"));
}

#[test]
fn k16_kernel_timer_smoke_artifacts_are_documented() {
    let root = repo_root();
    let smoke_script = root.join("tools/k16-kernel-timer-smoke.sh");
    let docs = root.join("docs/toolchains/k16-kernel-timer-smoke.md");

    let script = fs::read_to_string(&smoke_script).expect("kernel timer smoke script exists");
    assert!(script.contains("rust/guest/k16-kernel/Cargo.toml"));
    assert!(script.contains("--k16-target=kernel"));
    assert!(script.contains("k16-cpu-helpers"));
    assert!(script.contains("decode_k16_executable"));
    assert!(script.contains("K16eAbiKind::Kernel"));
    assert!(script.contains("boot_handoff_k16_from_guest_ram"));
    assert!(script.contains("advance_game_tick"));
    assert!(script.contains("syscall"));
    assert!(script.contains("signal=yield"));
    assert!(script.contains("SLEEP_TICKS"));
    assert!(script.contains("debug_suffix=7c7c53217c"));
    assert!(script.contains("continuation_r2=83"));
    assert!(script.contains("continuation_r3=0"));
    assert!(script.contains("continuation_r4=0"));
    assert!(script.contains("continuation_r5=0"));
    assert!(!script.contains("intc0"));
    assert!(!script.contains("RUX16"));

    let docs = fs::read_to_string(&docs).expect("kernel timer smoke docs exist");
    assert!(docs.contains("tools/k16-kernel-timer-smoke.sh"));
    assert!(docs.contains("rust/guest/k16-kernel"));
    assert!(docs.contains("k16-cpu-helpers"));
    assert!(docs.contains("timer0"));
    assert!(docs.contains("syscall"));
    assert!(docs.contains("READY"));
    assert!(docs.contains("SLEEP_TICKS"));
    assert!(docs.contains("debug_suffix=7c7c53217c"));
    assert!(docs.contains("continuation_r2=83"));
    assert!(docs.contains("continuation_r3=0"));
    assert!(docs.contains("continuation_r4=0"));
    assert!(docs.contains("continuation_r5=0"));
    assert!(!docs.contains("intc0"));
    assert!(!docs.contains("RUX16"));
}

#[test]
fn k16_bios_splash_uses_sleep_boundary() {
    let root = repo_root();
    let bios_source = root.join("guest/c/bios/bios.c");
    let boot_chain_source = root.join("guest/c/boot-chain/boot_chain.c");
    let boot_chain_header = root.join("guest/c/boot-chain/boot_chain.h");

    let source = fs::read_to_string(&bios_source).expect("K16 BIOS source exists");
    let boot_chain = fs::read_to_string(&boot_chain_source).expect("C boot-chain source exists");
    let boot_chain_header =
        fs::read_to_string(&boot_chain_header).expect("C boot-chain header exists");
    assert!(source.contains("sleep_ticks(20);"));
    assert!(source.contains("CONTROL_YIELD"));
    assert!(source.contains("K16E_ABI_KIND_BOOTLOADER"));
    assert!(source.contains("load_k16e_from_storage0"));
    assert!(boot_chain.contains("K16PT"));
    assert!(boot_chain.contains("K16FS"));
    assert!(boot_chain_header.contains("struct k16_loaded_image"));
    assert!(!source.contains("k16_rt::sleep_ticks"));
    assert!(!source.contains("k16_rt::yield_once"));
}

#[test]
fn k16_bootloader_is_c_built_and_loads_kernel() {
    let root = repo_root();
    let boot_source = root.join("guest/c/boot/boot.c");

    let source = fs::read_to_string(&boot_source).expect("K16 bootloader source exists");
    assert!(source.contains("K16 BOOT\\n"));
    assert!(source.contains("load_k16e_from_storage0"));
    assert!(source.contains("\"ROOT\""));
    assert!(source.contains("\"boot\""));
    assert!(source.contains("\"kernel.kx\""));
    assert!(source.contains("K16E_ABI_KIND_KERNEL"));
    assert!(!source.contains("k16_rt::halt_forever"));
}

#[test]
fn kernel_boot_chain_is_kernel_owned() {
    let root = repo_root();
    let workspace_manifest = root.join("rust/guest/Cargo.toml");
    let kernel_manifest = root.join("rust/guest/k16-kernel/Cargo.toml");
    let kernel_boot_chain = root.join("rust/guest/k16-kernel/src/boot_chain.rs");

    let workspace_manifest =
        fs::read_to_string(&workspace_manifest).expect("K16 guest workspace manifest exists");
    let kernel_manifest =
        fs::read_to_string(&kernel_manifest).expect("K16 kernel manifest exists");
    let kernel_boot_chain =
        fs::read_to_string(&kernel_boot_chain).expect("kernel-owned boot-chain source exists");

    assert!(
        !rust_guest_workspace_members(&workspace_manifest).contains(&"k16-boot-chain"),
        "Rust boot-chain support should be kernel-owned, not a standalone workspace member"
    );
    assert!(
        !root.join("rust/guest/k16-boot-chain").exists(),
        "standalone Rust boot-chain crate should be removed after C BIOS/bootloader migration"
    );
    assert!(!kernel_manifest.contains("k16-boot-chain"));
    assert!(kernel_boot_chain.contains("pub struct LoadedImage"));
    assert!(kernel_boot_chain.contains("pub struct LoadError"));
    assert!(kernel_boot_chain.contains("user_memory_end_from_boot_info"));
}

#[test]
fn kernel_image_parser_is_kernel_owned() {
    let root = repo_root();
    let workspace_manifest = root.join("rust/guest/Cargo.toml");
    let kernel_manifest = root.join("rust/guest/k16-kernel/Cargo.toml");
    let kernel_image = root.join("rust/guest/k16-kernel/src/image.rs");

    let workspace_manifest =
        fs::read_to_string(&workspace_manifest).expect("K16 guest workspace manifest exists");
    let kernel_manifest =
        fs::read_to_string(&kernel_manifest).expect("K16 kernel manifest exists");
    let kernel_image =
        fs::read_to_string(&kernel_image).expect("kernel-owned image parser source exists");

    assert!(
        !rust_guest_workspace_members(&workspace_manifest).contains(&"k16-image"),
        "Rust image parser should be kernel-owned, not a standalone workspace member"
    );
    assert!(
        !root.join("rust/guest/k16-image").exists(),
        "standalone Rust image parser crate should be removed"
    );
    assert!(!kernel_manifest.contains("k16-image"));
    assert!(kernel_image.contains("pub enum K16eAbiKind"));
    assert!(kernel_image.contains("parse_dynamic_k16e_v5"));
    assert!(kernel_image.contains("SHARED_K16E_V4_HEADER_SIZE"));
}

#[test]
fn legacy_rust_userland_crates_are_removed_after_c_migration() {
    let root = repo_root();
    let workspace_manifest = root.join("rust/guest/Cargo.toml");
    let workspace_manifest =
        fs::read_to_string(&workspace_manifest).expect("K16 guest workspace manifest exists");
    let removed_members = [
        "k16-uname",
        "k16-cat",
        "k16-write",
        "k16-rm",
        "k16-mkdir",
        "k16-rmdir",
        "k16-stat",
        "k16-ls",
        "k16-cp",
        "k16-mv",
        "k16-hosted-cat",
        "k16-hosted-hello",
        "k16-bios",
        "k16-boot",
    ];

    for member in removed_members {
        assert!(
            !rust_guest_workspace_members(&workspace_manifest).contains(&member),
            "legacy Rust userland member {member} should not remain in rust/guest/Cargo.toml"
        );
        assert!(
            !root.join("rust/guest").join(member).exists(),
            "legacy Rust userland directory rust/guest/{member} should be removed"
        );
    }
}

#[test]
fn k16_c_libc_cat_has_minimal_libkraft_abi_sources() {
    let root = repo_root();
    let header = root.join("guest/c/libc/include/kraft/syscalls.h");
    let fs_header = root.join("guest/c/libc/include/kraft/fs.h");
    let process_header = root.join("guest/c/libc/include/kraft/process.h");
    let syscalls = root.join("guest/c/libc/syscalls.c");
    let unistd = root.join("guest/c/libc/include/unistd.h");
    let fcntl = root.join("guest/c/libc/include/fcntl.h");
    let string = root.join("guest/c/libc/include/string.h");
    let startup = root.join("guest/c/libc/crt0.c");
    let arch_runtime = root.join("guest/c/arch/k16/cpu-helpers.kasm");
    let cat = root.join("guest/c/coreutils/cat.c");
    let init = root.join("guest/c/init/init.c");
    let shell = root.join("guest/c/shell/shell.c");
    let libkraft = root.join("guest/c/libkraft/libkraft.c");
    let cp = root.join("guest/c/coreutils/cp.c");
    let ls = root.join("guest/c/coreutils/ls.c");
    let mkdir = root.join("guest/c/coreutils/mkdir.c");
    let mv = root.join("guest/c/coreutils/mv.c");
    let rm = root.join("guest/c/coreutils/rm.c");
    let rmdir = root.join("guest/c/coreutils/rmdir.c");
    let stat = root.join("guest/c/coreutils/stat.c");
    let uname = root.join("guest/c/coreutils/uname.c");
    let write = root.join("guest/c/coreutils/write.c");

    let header = fs::read_to_string(&header).expect("C libkraft syscall header exists");
    assert!(header.contains("extern int __kraft_sys_open(const char *path, unsigned int len,"));
    assert!(header.contains("__asm__(\"kraft_sys_open\")"));
    assert!(header.contains("int kraft_open(const char *path, unsigned int flags);"));
    assert!(header.contains("extern int __kraft_sys_mkdir"));
    assert!(header.contains("__asm__(\"kraft_sys_mkdir\")"));
    assert!(header.contains("extern int __kraft_sys_rmdir"));
    assert!(header.contains("__asm__(\"kraft_sys_rmdir\")"));
    assert!(header.contains("extern int __kraft_sys_unlink"));
    assert!(header.contains("__asm__(\"kraft_sys_unlink\")"));
    assert!(header.contains("extern int __kraft_sys_read_dir"));
    assert!(header.contains("__asm__(\"kraft_sys_read_dir\")"));
    assert!(header.contains("extern int __kraft_sys_stat"));
    assert!(header.contains("__asm__(\"kraft_sys_stat\")"));
    assert!(header.contains("extern int __kraft_sys_rename"));
    assert!(header.contains("__asm__(\"kraft_sys_rename\")"));
    assert!(header.contains("extern int __kraft_sys_spawn(const void *request, unsigned int len)"));
    assert!(header.contains("__asm__(\"kraft_sys_spawn\")"));
    assert!(header.contains("extern int __kraft_sys_wait(unsigned int pid, int *status)"));
    assert!(header.contains("__asm__(\"kraft_sys_wait\")"));
    assert!(header.contains("extern int __kraft_sys_run(const void *request, unsigned int len)"));
    assert!(header.contains("__asm__(\"kraft_sys_run\")"));
    assert!(
        header.contains("int kraft_read_dir(const char *path, char *out, unsigned int out_len);")
    );
    assert!(header.contains("int kraft_stat(const char *path, struct kraft_stat *metadata);"));
    assert!(header.contains("int kraft_rename(const char *old_path, const char *new_path);"));
    assert!(header.contains("int kraft_mkdir(const char *path);"));
    assert!(header.contains("int kraft_rmdir(const char *path);"));
    assert!(header.contains("int kraft_unlink(const char *path);"));
    assert!(header.contains(
        "int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);"
    ));
    assert!(header
        .contains("int kraft_run_with_args(const char *path, int argc, const char *const *argv);"));
    assert!(header.contains("int kraft_wait(int pid, int *status);"));
    assert!(header.contains("int read(int fd, void *buffer, unsigned int count)"));
    assert!(header.contains("__asm__(\"kraft_sys_read\")"));
    assert!(header.contains("int write(int fd, const void *buffer, unsigned int count)"));
    assert!(header.contains("__asm__(\"kraft_sys_write\")"));
    assert!(header.contains("int close(int fd) __asm__(\"kraft_sys_close\");"));
    assert!(header.contains("void *sbrk(int increment) __asm__(\"kraft_sys_sbrk\");"));
    assert!(header.contains("void _exit(int status) __asm__(\"kraft_sys_exit\");"));
    assert!(header.contains("#define KRAFT_FD_STDOUT 1"));
    assert!(header.contains("#define KRAFT_OPEN_READ_ONLY 0"));
    assert!(header.contains("#define KRAFT_OPEN_WRITE_ONLY 1"));
    assert!(header.contains("#define KRAFT_OPEN_APPEND 8"));

    let arch_runtime = fs::read_to_string(&arch_runtime).expect("source K16 arch runtime exists");
    assert!(arch_runtime.contains(".function __k16_syscall1"));
    assert!(arch_runtime.contains(".function __k16_syscall3"));
    assert!(arch_runtime.contains(".function __k16_halt_once"));
    assert!(arch_runtime.contains(".function __k16_open_syscall"));
    assert!(arch_runtime.contains("syscall r1"));

    let fs_header = fs::read_to_string(&fs_header).expect("C libkraft fs header exists");
    assert!(fs_header.contains("#define KRAFT_READ_DIR_REQUEST_MAGIC 0x52494452u"));
    assert!(fs_header.contains("#define KRAFT_RENAME_REQUEST_MAGIC 0x4d414e52u"));
    assert!(fs_header.contains("#define KRAFT_MAX_READ_DIR_PATH_BYTES 228"));
    assert!(fs_header.contains("#define KRAFT_MAX_RENAME_PATH_BYTES 228"));
    assert!(fs_header.contains("#define KRAFT_MAX_RENAME_REQUEST_BYTES 468"));
    assert!(fs_header.contains("#define KRAFT_STAT_METADATA_BYTES 16"));
    assert!(fs_header.contains("struct kraft_stat"));
    assert!(fs_header.contains("unsigned int file_type;"));
    assert!(fs_header.contains("unsigned int size_bytes;"));
    assert!(fs_header
        .contains("int kraft_read_dir(const char *path, char *out, unsigned int out_len);"));
    assert!(fs_header.contains("int kraft_stat(const char *path, struct kraft_stat *metadata);"));
    assert!(fs_header.contains("int kraft_rename(const char *old_path, const char *new_path);"));
    assert!(fs_header
        .contains("#define read_dir(path, out, out_len) kraft_read_dir((path), (out), (out_len))"));
    assert!(fs_header.contains("#define stat(path, metadata) kraft_stat((path), (metadata))"));
    assert!(fs_header
        .contains("#define rename(old_path, new_path) kraft_rename((old_path), (new_path))"));

    let process_header =
        fs::read_to_string(&process_header).expect("C libkraft process header exists");
    assert!(process_header.contains("#define KRAFT_SPAWN_ARGV_REQUEST_MAGIC 0x57415053u"));
    assert!(process_header.contains("#define KRAFT_RUN_ARGV_REQUEST_MAGIC 0x47524152u"));
    assert!(process_header.contains("#define KRAFT_MAX_PROCESS_ARGS 4"));
    assert!(process_header.contains("#define KRAFT_MAX_PROCESS_PATH_BYTES 61"));
    assert!(process_header.contains("#define KRAFT_MAX_PROCESS_ARG_BYTES 128"));
    assert!(process_header.contains("#define KRAFT_MAX_SPAWN_ARGV_REQUEST_BYTES 601"));
    assert!(process_header.contains(
        "int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);"
    ));
    assert!(process_header
        .contains("int kraft_run_with_args(const char *path, int argc, const char *const *argv);"));
    assert!(process_header.contains("int kraft_wait(int pid, int *status);"));

    let syscalls = fs::read_to_string(&syscalls).expect("C libkraft syscall source exists");
    assert!(syscalls.contains("#include <kraft/syscalls.h>"));
    assert!(syscalls.contains("#include <string.h>"));
    assert!(syscalls.contains("int kraft_open(const char *path, unsigned int flags)"));
    assert!(syscalls.contains("__kraft_sys_open(path, strlen(path), flags)"));
    assert!(syscalls.contains("int kraft_mkdir(const char *path)"));
    assert!(syscalls.contains("__kraft_sys_mkdir(path, strlen(path))"));
    assert!(syscalls.contains("int kraft_rmdir(const char *path)"));
    assert!(syscalls.contains("__kraft_sys_rmdir(path, strlen(path))"));
    assert!(syscalls.contains("int kraft_unlink(const char *path)"));
    assert!(syscalls.contains("__kraft_sys_unlink(path, strlen(path))"));
    assert!(
        syscalls.contains("int kraft_read_dir(const char *path, char *out, unsigned int out_len)")
    );
    assert!(syscalls.contains("put_u32_le(request + 0, KRAFT_READ_DIR_REQUEST_MAGIC)"));
    assert!(syscalls.contains("__kraft_sys_read_dir(request, request_len)"));
    assert!(syscalls.contains("int kraft_stat(const char *path, struct kraft_stat *metadata)"));
    assert!(syscalls.contains("__kraft_sys_stat(path, path_len, metadata)"));
    assert!(syscalls.contains("int kraft_rename(const char *old_path, const char *new_path)"));
    assert!(syscalls.contains("put_u32_le(request + 0, KRAFT_RENAME_REQUEST_MAGIC)"));
    assert!(syscalls.contains("__kraft_sys_rename(request, request_len)"));
    assert!(syscalls.contains("int kraft_spawn_with_args(const char *path, int argc,"));
    assert!(syscalls.contains("kraft_process_with_args(KRAFT_SPAWN_ARGV_REQUEST_MAGIC"));
    assert!(syscalls.contains("__kraft_sys_spawn"));
    assert!(syscalls.contains("int kraft_run_with_args(const char *path, int argc,"));
    assert!(syscalls.contains("kraft_process_with_args(KRAFT_RUN_ARGV_REQUEST_MAGIC"));
    assert!(syscalls.contains("__kraft_sys_run"));
    assert!(syscalls.contains("int kraft_wait(int pid, int *status)"));
    assert!(syscalls.contains("__kraft_sys_wait((unsigned int)pid, status)"));

    let libkraft = fs::read_to_string(&libkraft).expect("C libkraft shared provider exists");
    for symbol in [
        "int kraft_sys_open(const char *path, unsigned int len, unsigned int flags)",
        "int kraft_sys_read(unsigned int fd, void *buffer, unsigned int len)",
        "int kraft_sys_write(unsigned int fd, const void *buffer, unsigned int len)",
        "int kraft_sys_close(unsigned int fd)",
        "int kraft_sys_spawn(const void *request, unsigned int len)",
        "int kraft_sys_run(const void *request, unsigned int len)",
        "int kraft_sys_wait(unsigned int pid, int *status)",
        "void kraft_sys_exit(int status)",
    ] {
        assert!(
            libkraft.contains(symbol),
            "C libkraft provider should define {symbol}"
        );
    }
    assert!(libkraft.contains("__k16_open_syscall("));
    assert!(libkraft.contains("__k16_syscall3(K16_SYSCALL_RUN"));
    assert!(!libkraft.contains("int kraft_open("));

    let unistd = fs::read_to_string(&unistd).expect("C libc-lite unistd header exists");
    assert!(unistd.contains("int open(const char *path, int flags);"));
    assert!(unistd.contains("int read(int fd, void *buffer, unsigned int count)"));
    assert!(unistd.contains("__asm__(\"kraft_sys_read\")"));
    assert!(unistd.contains("int write(int fd, const void *buffer, unsigned int count)"));
    assert!(unistd.contains("__asm__(\"kraft_sys_write\")"));
    assert!(unistd.contains("int close(int fd) __asm__(\"kraft_sys_close\");"));
    assert!(unistd.contains("#define STDOUT_FILENO KRAFT_FD_STDOUT"));
    assert!(unistd.contains("int kraft_mkdir(const char *path);"));
    assert!(unistd.contains("int kraft_rmdir(const char *path);"));
    assert!(unistd.contains("int kraft_unlink(const char *path);"));
    assert!(unistd.contains("#define mkdir(path) kraft_mkdir(path)"));
    assert!(unistd.contains("#define rmdir(path) kraft_rmdir(path)"));
    assert!(unistd.contains("#define unlink(path) kraft_unlink(path)"));

    let fcntl = fs::read_to_string(&fcntl).expect("C libc-lite fcntl header exists");
    assert!(fcntl.contains("#define O_RDONLY KRAFT_OPEN_READ_ONLY"));
    assert!(fcntl.contains("#define O_WRONLY KRAFT_OPEN_WRITE_ONLY"));
    assert!(fcntl.contains("#define O_TRUNC KRAFT_OPEN_TRUNCATE"));

    let string = fs::read_to_string(&string).expect("C libc-lite string header exists");
    assert!(string.contains("unsigned int strlen(const char *text)"));
    assert!(string.contains("int strcmp(const char *left, const char *right)"));

    let startup = fs::read_to_string(&startup).expect("C hosted startup source exists");
    assert!(startup.contains("int kraft_main(int argc, char **argv);"));
    assert!(
        startup.contains("int main(unsigned int raw_argc, const struct kraft_raw_arg *raw_argv)")
    );
    assert!(startup.contains("return kraft_main((int)argc, argv);"));

    let cat = fs::read_to_string(&cat).expect("C hosted cat source exists");
    assert!(cat.contains("#include <fcntl.h>"));
    assert!(cat.contains("#include <unistd.h>"));
    assert!(cat.contains("open(path, O_RDONLY)"));
    assert!(cat.contains("read(fd, buffer, sizeof(buffer))"));
    assert!(cat.contains("write_all(STDOUT_FILENO"));
    assert!(cat.contains("close(fd)"));
    assert!(
        !cat.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let init = fs::read_to_string(&init).expect("C init source exists");
    assert!(init.contains("#include <kraft/process.h>"));
    assert!(init.contains("#include <unistd.h>"));
    assert!(init.contains("#define SHELL_PATH \"/bin/shell.kx\""));
    assert!(init.contains("const char *shell_args[] = {SHELL_PATH};"));
    assert!(init.contains("kraft_spawn_with_args(SHELL_PATH, 1, shell_args)"));
    assert!(init.contains("kraft_wait(pid, &status)"));
    assert!(init.contains("if (status == 0)"));
    assert!(init.contains("_exit(status)"));
    assert!(!init.contains("stdio.h"), "C init must not depend on stdio");

    let shell = fs::read_to_string(&shell).expect("C shell source exists");
    assert!(shell.contains("#include <kraft/fs.h>"));
    assert!(shell.contains("#include <kraft/process.h>"));
    assert!(shell.contains("#include <kraft/syscalls.h>"));
    assert!(shell.contains("#include <unistd.h>"));
    assert!(shell.contains("#define PROMPT \"K16> \""));
    assert!(shell.contains("char cwd[KRAFT_MAX_SHELL_PATH_BYTES + 1]"));
    assert!(shell.contains("char input[KRAFT_SHELL_INPUT_CAPACITY]"));
    assert!(shell.contains("read(STDIN_FILENO, read_buffer, sizeof(read_buffer))"));
    assert!(shell.contains("static void dispatch_command("));
    assert!(shell.contains("static unsigned int run_exec("));
    assert!(shell.contains("static unsigned char ticks_bytes[8];"));
    assert!(shell.contains("__k16_syscall1("));
    assert!(shell.contains("KRAFT_SYSCALL_GAME_TICKS"));
    assert!(shell.contains("kraft_run_with_args(program_path, argc, argv)"));
    assert!(shell.contains("should_resolve_path_arg(name, raw_args, index)"));
    assert!(!shell.contains("ALLOC_ALIAS"));
    assert!(!shell.contains("ALLOC_PROGRAM"));
    assert!(
        !shell.contains("stdio.h"),
        "C shell must not depend on stdio"
    );

    let cp = fs::read_to_string(&cp).expect("C hosted cp source exists");
    assert!(cp.contains("#include <fcntl.h>"));
    assert!(cp.contains("#include <string.h>"));
    assert!(cp.contains("#include <unistd.h>"));
    assert!(cp.contains("argc != 3"));
    assert!(cp.contains("open(source_path, O_RDONLY)"));
    assert!(cp.contains("open(destination_path, O_WRONLY | O_CREAT | O_TRUNC)"));
    assert!(cp.contains("read(source, buffer, sizeof(buffer))"));
    assert!(cp.contains("write_all(destination"));
    assert!(cp.contains("write_text(STDOUT_FILENO, \"COPIED \")"));
    assert!(cp.contains("status_name(status, \"IO\")"));
    assert!(
        !cp.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let ls = fs::read_to_string(&ls).expect("C hosted ls source exists");
    assert!(ls.contains("#include <kraft/fs.h>"));
    assert!(ls.contains("#include <unistd.h>"));
    assert!(ls.contains("const char *path = argc > 1 ? argv[index] : \"/bin\""));
    assert!(ls.contains("read_dir(path, buffer, sizeof(buffer))"));
    assert!(ls.contains("stat(child_path, &metadata)"));
    assert!(ls.contains("metadata.file_type == KRAFT_FILE_TYPE_DIRECTORY"));
    assert!(ls.contains("status_name(status, \"READDIR\")"));
    assert!(
        !ls.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let mkdir = fs::read_to_string(&mkdir).expect("C hosted mkdir source exists");
    assert!(mkdir.contains("#include <unistd.h>"));
    assert!(mkdir.contains("mkdir(path)"));
    assert!(mkdir.contains("write_text(STDOUT_FILENO, \"CREATED \")"));
    assert!(mkdir.contains("status_name(status, \"MKDIR\")"));
    assert!(
        !mkdir.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let mv = fs::read_to_string(&mv).expect("C hosted mv source exists");
    assert!(mv.contains("#include <kraft/fs.h>"));
    assert!(mv.contains("#include <unistd.h>"));
    assert!(mv.contains("argc != 3"));
    assert!(mv.contains("stat(source_path, &metadata)"));
    assert!(mv.contains("metadata.file_type != KRAFT_FILE_TYPE_REGULAR"));
    assert!(mv.contains("stat(destination_path, &metadata) == 0"));
    assert!(mv.contains("rename(source_path, destination_path)"));
    assert!(mv.contains("write_text(STDOUT_FILENO, \"MOVED \")"));
    assert!(mv.contains("status_name(status, \"RENAME\")"));
    assert!(
        !mv.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let rm = fs::read_to_string(&rm).expect("C hosted rm source exists");
    assert!(rm.contains("#include <unistd.h>"));
    assert!(rm.contains("unlink(path)"));
    assert!(rm.contains("write_text(STDOUT_FILENO, \"REMOVED \")"));
    assert!(rm.contains("status_name(status, \"UNLINK\")"));
    assert!(
        !rm.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let rmdir = fs::read_to_string(&rmdir).expect("C hosted rmdir source exists");
    assert!(rmdir.contains("#include <unistd.h>"));
    assert!(rmdir.contains("rmdir(path)"));
    assert!(rmdir.contains("write_text(STDOUT_FILENO, \"REMOVED \")"));
    assert!(rmdir.contains("status_name(status, \"RMDIR\")"));
    assert!(
        !rmdir.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let stat = fs::read_to_string(&stat).expect("C hosted stat source exists");
    assert!(stat.contains("#include <kraft/fs.h>"));
    assert!(stat.contains("#include <unistd.h>"));
    assert!(stat.contains("stat(path, &metadata)"));
    assert!(stat.contains("metadata.file_type == KRAFT_FILE_TYPE_REGULAR"));
    assert!(stat.contains("metadata.file_type == KRAFT_FILE_TYPE_DIRECTORY"));
    assert!(stat.contains("write_decimal(STDOUT_FILENO, metadata.size_bytes)"));
    assert!(stat.contains("status_name(status, \"STAT\")"));
    assert!(
        !stat.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let uname = fs::read_to_string(&uname).expect("C hosted uname source exists");
    assert!(uname.contains("#include <unistd.h>"));
    assert!(uname.contains("write_all(STDOUT_FILENO"));
    assert!(uname.contains("\"K16\\n\""));
    assert!(
        !uname.contains("stdio.h"),
        "C hosted baseline must not depend on stdio"
    );

    let write = fs::read_to_string(&write).expect("C hosted write source exists");
    assert!(write.contains("#include <fcntl.h>"));
    assert!(write.contains("#include <string.h>"));
    assert!(write.contains("#include <unistd.h>"));
    assert!(write.contains("O_WRONLY | O_CREAT | O_TRUNC"));
    assert!(write.contains("O_WRONLY | O_CREAT | O_APPEND"));
    assert!(write.contains("unsigned int len = strlen(payload)"));
    assert!(write.contains("write_all(fd, payload, len)"));
}

#[test]
fn k16_guest_rust_migration_map_covers_workspace_crates() {
    let root = repo_root();
    let workspace_manifest = root.join("rust/guest/Cargo.toml");
    let migration_map = root.join("docs/toolchains/k16-guest-rust-migration-map.md");

    let workspace_manifest =
        fs::read_to_string(&workspace_manifest).expect("K16 guest workspace manifest exists");
    let migration_map = fs::read_to_string(&migration_map).expect("K16 guest migration map exists");

    for member in rust_guest_workspace_members(&workspace_manifest) {
        let path = format!("`rust/guest/{member}`");
        assert!(
            migration_map.contains(&path),
            "K16 guest Rust migration map must classify {path}"
        );
    }

    assert!(migration_map.contains("C-first userland/coreutils policy"));
    assert!(migration_map.contains("Rust kernel remains Rust for now"));
    assert!(migration_map.contains("Next Production C Candidates"));
    assert!(migration_map.contains("Development/test-only"));
}

#[test]
fn removed_kraft_std_layer_no_longer_exists() {
    let root = repo_root();
    let workspace_manifest = root.join("rust/guest/Cargo.toml");
    let k16_rt_manifest = root.join("rust/guest/k16-rt/Cargo.toml");
    let removed_doc = root.join("docs/toolchains/kraft-std.md");
    let removed_source = root.join("rust/guest/kraft-std/src/lib.rs");

    let workspace_manifest =
        fs::read_to_string(&workspace_manifest).expect("guest workspace manifest exists");
    let k16_rt_manifest = fs::read_to_string(&k16_rt_manifest).expect("k16-rt manifest exists");
    assert!(
        !workspace_manifest.contains("kraft-std"),
        "guest workspace must not include removed kraft-std"
    );
    assert!(
        !k16_rt_manifest.contains("kraft-std"),
        "k16-rt must not depend on removed kraft-std"
    );
    assert!(
        !removed_doc.exists(),
        "removed kraft-std docs should not remain current documentation"
    );
    assert!(
        !removed_source.exists(),
        "removed kraft-std source should not remain in the guest tree"
    );
}

#[test]
fn k16_rt_no_longer_exports_userland_syscall_wrappers() {
    let root = repo_root();
    let runtime_lib =
        fs::read_to_string(root.join("rust/guest/k16-rt/src/lib.rs")).expect("k16-rt lib exists");
    let runtime_trap =
        fs::read_to_string(root.join("rust/guest/k16-rt/src/trap.rs")).expect("k16-rt trap exists");

    for symbol in [
        "write_syscall",
        "read_syscall",
        "open_syscall",
        "close_syscall",
        "brk_syscall",
        "sbrk_syscall",
        "run_argv_syscall",
        "spawn_argv_syscall",
        "wait_syscall",
        "seek_syscall",
        "unlink_syscall",
        "mkdir_syscall",
        "rmdir_syscall",
        "rename_syscall",
        "read_dir_syscall",
        "stat_syscall",
        "game_ticks_syscall",
        "debug_marker",
        "debug_write_byte",
        "yield_syscall",
        "sleep_ticks_syscall",
        "exit_syscall",
        "syscall_once",
        "syscall0",
        "syscall1",
        "syscall3",
    ] {
        assert!(
            !runtime_lib.contains(symbol),
            "k16-rt must not re-export removed Rust userland wrapper {symbol}",
        );
        assert!(
            !runtime_trap.contains(&format!("pub fn {symbol}")),
            "k16-rt trap module must not define removed Rust userland wrapper {symbol}",
        );
    }

    for symbol in [
        "iret_with_r0",
        "save_trap_frame",
        "syscall_arg0",
        "syscall_arg2",
    ] {
        assert!(
            runtime_lib.contains(symbol),
            "k16-rt must keep kernel/runtime primitive {symbol}",
        );
    }
}

#[test]
fn kraftos_rust_std_sys_hooks_live_in_named_modules() {
    let root = repo_root();
    let std_sys = root.join("toolchains/Compukter-Kraft-rust/library/std/src/sys");

    let alloc_mod = fs::read_to_string(std_sys.join("alloc/mod.rs")).expect("alloc sys mod exists");
    assert!(alloc_mod.contains("target_os = \"kraftos\""));
    assert!(alloc_mod.contains("mod kraftos;"));
    assert!(!alloc_mod.contains("target_os = \"kraftos\" => {\n        mod unsupported;"));

    let stdio_mod = fs::read_to_string(std_sys.join("stdio/mod.rs")).expect("stdio sys mod exists");
    assert!(stdio_mod.contains("target_os = \"kraftos\""));
    assert!(stdio_mod.contains("mod kraftos;"));
    assert!(stdio_mod.contains("pub use kraftos::*;"));

    let fs_mod = fs::read_to_string(std_sys.join("fs/mod.rs")).expect("fs sys mod exists");
    assert!(fs_mod.contains("target_os = \"kraftos\""));
    assert!(fs_mod.contains("mod kraftos;"));
    assert!(fs_mod.contains("use kraftos as imp;"));

    let args_mod = fs::read_to_string(std_sys.join("args/mod.rs")).expect("args sys mod exists");
    assert!(args_mod.contains("target_os = \"kraftos\""));
    assert!(args_mod.contains("mod kraftos;"));
    assert!(args_mod.contains("pub use kraftos::*;"));

    let pal_mod = fs::read_to_string(std_sys.join("pal/mod.rs")).expect("PAL sys mod exists");
    assert!(pal_mod.contains("target_os = \"kraftos\""));
    assert!(pal_mod.contains("mod kraftos;"));
    assert!(pal_mod.contains("pub use self::kraftos::*;"));

    let unsupported_stdio =
        fs::read_to_string(std_sys.join("stdio/unsupported.rs")).expect("unsupported stdio exists");
    assert!(!unsupported_stdio.contains("target_os = \"kraftos\""));
    assert!(!unsupported_stdio.contains("__k16_write_syscall"));

    let unsupported_fs =
        fs::read_to_string(std_sys.join("fs/unsupported.rs")).expect("unsupported fs exists");
    assert!(!unsupported_fs.contains("__k16_open_syscall"));
    assert!(!unsupported_fs.contains("__k16_read_syscall"));
    assert!(!unsupported_fs.contains("__k16_close_syscall"));

    let unsupported_args =
        fs::read_to_string(std_sys.join("args/unsupported.rs")).expect("unsupported args exists");
    assert!(!unsupported_args.contains("target_os = \"kraftos\""));
    assert!(!unsupported_args.contains("ARG_ENTRY_BYTES"));

    let kraftos_stdio =
        fs::read_to_string(std_sys.join("stdio/kraftos.rs")).expect("KraftOS stdio exists");
    assert!(kraftos_stdio.contains("__k16_write_syscall"));
    assert!(kraftos_stdio.contains("FD_STDOUT"));

    let kraftos_alloc =
        fs::read_to_string(std_sys.join("alloc/kraftos.rs")).expect("KraftOS alloc exists");
    assert!(kraftos_alloc.contains("__k16_sbrk_syscall"));

    let kraftos_fs = fs::read_to_string(std_sys.join("fs/kraftos.rs")).expect("KraftOS fs exists");
    assert!(kraftos_fs.contains("__k16_open_syscall"));
    assert!(kraftos_fs.contains("__k16_read_syscall"));
    assert!(kraftos_fs.contains("__k16_close_syscall"));
    assert!(kraftos_fs.contains("OPEN_READ_ONLY"));

    let kraftos_args =
        fs::read_to_string(std_sys.join("args/kraftos.rs")).expect("KraftOS args exists");
    assert!(kraftos_args.contains("ARG_ENTRY_BYTES"));
    assert!(kraftos_args.contains("OsString::from_encoded_bytes_unchecked"));

    let kraftos_pal =
        fs::read_to_string(std_sys.join("pal/kraftos/mod.rs")).expect("KraftOS PAL exists");
    assert!(kraftos_pal.contains("crate::sys::args::init"));
}

#[test]
fn rust_nocore_smoke_artifacts_are_documented_and_strict() {
    let root = repo_root();
    let target_spec = root.join("tools/k16-unknown-kraftos.json");
    let llvm_smoke_script = root.join("tools/k16-llvm-smoke.sh");
    let clang_smoke_script = root.join("tools/k16-clang-smoke.sh");
    let smoke_script = root.join("tools/k16-rust-nocore-smoke.sh");
    let core_smoke_script = root.join("tools/k16-rust-core-smoke.sh");
    let bootstrap_probe = root.join("tools/k16-rustc-bootstrap-probe.sh");
    let runtime_helpers = root.join("rust/guest/k16-rt/src/no_core_helpers.rs");
    let retired_host_runtime = root.join("rust/host/k16-tools/runtime");
    let llvm_docs = root.join("docs/toolchains/k16-llvm-smoke.md");
    let clang_docs = root.join("docs/toolchains/k16-clang-smoke.md");
    let docs = root.join("docs/toolchains/k16-rust-nocore-smoke.md");
    let core_docs = root.join("docs/toolchains/k16-rust-core-smoke.md");
    let bootstrap_docs = root.join("docs/toolchains/k16-rustc-bootstrap.md");
    let llvm_submodule_docs = root.join("docs/toolchains/k16-llvm-submodule.md");
    let feasibility_docs = root.join("docs/toolchains/k16-rust-feasibility.md");
    let strategy_docs = root.join("docs/toolchains/k16-language-strategy.md");
    let retired_public_paths = [
        "tools/k16-unknown-ruxos.json",
        "tools/rux16-unknown-ruxos.json",
        "tools/rux16-llvm-smoke.sh",
        "tools/rux16-clang-smoke.sh",
        "tools/rux16-rust-nocore-smoke.sh",
        "tools/rux16-rustc-bootstrap-probe.sh",
        "docs/toolchains/rux16-llvm-smoke.md",
        "docs/toolchains/rux16-clang-smoke.md",
        "docs/toolchains/rux16-llvm-submodule.md",
        "docs/toolchains/rux16-rust-feasibility.md",
        "docs/toolchains/rux16-rust-nocore-smoke.md",
        "docs/toolchains/rux16-rustc-bootstrap.md",
        "docs/toolchains/rux16-language-strategy.md",
    ];

    for path in retired_public_paths {
        assert!(
            !root.join(path).exists(),
            "machine toolchain surface should not keep retired public path `{path}`"
        );
    }

    let spec = fs::read_to_string(&target_spec).expect("K16 Rust target spec exists");
    assert!(spec.contains("\"llvm-target\": \"k16\""));
    assert!(spec.contains("\"panic-strategy\": \"abort\""));
    assert!(spec.contains("\"target-pointer-width\": 32"));
    assert!(!spec.contains("\"target-pointer-width\": \"32\""));
    assert!(spec.contains("\"executables\": true"));
    assert!(!spec.contains("\"executables\": false"));

    let llvm_smoke = fs::read_to_string(&llvm_smoke_script).expect("LLVM smoke script exists");
    assert!(llvm_smoke.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(llvm_smoke.contains(".toolchain/build/cargo/k16-tools"));
    assert!(llvm_smoke.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(llvm_smoke.contains("K16_CARGO_MANIFEST"));
    assert!(!llvm_smoke.contains("RUX_CARGO_MANIFEST"));
    assert!(!llvm_smoke.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin"));
    assert!(llvm_smoke.contains("--bin k16"));
    assert!(llvm_smoke.contains("main.kx"));
    assert!(llvm_smoke.contains("call-helper.kx"));
    assert!(llvm_smoke.contains("stack-local-main.kx"));
    assert!(!llvm_smoke.contains("--bin rux"));
    assert!(!llvm_smoke.contains(".k16e"));

    let clang_smoke = fs::read_to_string(&clang_smoke_script).expect("Clang smoke script exists");
    assert!(clang_smoke.contains(".toolchain/build/llvm/k16/bin"));
    assert!(clang_smoke.contains(".toolchain/build/cargo/k16-tools"));
    assert!(clang_smoke.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(clang_smoke.contains("K16_CARGO_MANIFEST"));
    assert!(!clang_smoke.contains("RUX_CARGO_MANIFEST"));
    assert!(!clang_smoke.contains("toolchains/Compukter-Kraft-llvm/build-k16/bin"));
    assert!(clang_smoke.contains("--bin k16"));
    assert!(clang_smoke.contains("main.kx"));
    assert!(!clang_smoke.contains("--bin rux"));
    assert!(!clang_smoke.contains(".k16e"));

    let script = fs::read_to_string(&smoke_script).expect("Rust no_core smoke script exists");
    assert!(script.contains("K16_RUSTC"));
    assert!(script.contains("K16_LLVM_BIN_DIR"));
    assert!(script.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(script.contains(".toolchain/build/cargo/k16-tools"));
    assert!(script.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(script.contains("K16_CARGO_MANIFEST"));
    assert!(!script.contains("RUX_CARGO_MANIFEST"));
    assert!(script.contains("K16_RUST_TARGET_JSON"));
    assert!(script.contains("tools/k16-unknown-kraftos.json"));
    assert!(script.contains("#![no_core]"));
    assert!(script.contains("#![no_main]"));
    assert!(script.contains("meta_sized"));
    assert!(script.contains("pointee_sized"));
    assert!(script.contains("k16-memory-helpers"));
    assert!(script.contains("\"$WORK_DIR/helpers.o\""));
    assert!(script.contains("main.kx"));
    assert!(script.contains("debug_bytes=2a"));
    assert!(script.contains("--bin k16"));
    assert!(!script.contains("--bin rux"));
    assert!(!script.contains(".k16e"));
    assert!(!script.contains("|| true"));
    assert!(!script.contains("RUX16_RUSTC"));
    assert!(!script.contains("RUX16_LLVM_BIN_DIR"));
    assert!(!script.contains("RUX16_RUST_TARGET_JSON"));

    let core_script =
        fs::read_to_string(&core_smoke_script).expect("Rust core smoke script exists");
    assert!(core_script.contains("-Z build-std=core"));
    assert!(core_script.contains("-Z json-target-spec"));
    assert!(core_script.contains("[lib]"));
    assert!(core_script.contains("--lib"));
    assert!(core_script.contains("RUSTFLAGS=\"-Copt-level=z -Cjump-tables=no -Cdebuginfo=0\""));
    assert!(core_script.contains("-Copt-level=z"));
    assert!(core_script.contains("-Cjump-tables=no"));
    assert!(core_script.contains("-Cdebuginfo=0"));
    assert!(core_script.contains("#![no_std]"));
    assert!(!core_script.contains("[[bin]]"));
    assert!(!core_script.contains("#![no_main]"));
    assert!(core_script.contains("core::hint::spin_loop"));
    assert!(core_script.contains("RUSTC_BOOTSTRAP"));
    assert!(core_script.contains("K16_KEEP_WORK_DIR"));
    assert!(core_script.contains("K16_RUSTC"));
    assert!(core_script.contains("K16_LLVM_BIN_DIR"));
    assert!(core_script.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(core_script.contains(".toolchain/build/cargo/k16-tools"));
    assert!(core_script.contains("K16_HOST_CARGO_TARGET_DIR"));
    assert!(core_script.contains("K16_RUST_TARGET_JSON"));
    assert!(core_script.contains("tools/k16-unknown-kraftos.json"));
    assert!(core_script.contains("k16-memory-helpers"));
    assert!(core_script.contains("K16 Rust core build failed."));
    assert!(core_script.contains("cargo-rustc.stderr"));
    assert!(core_script.contains("main.kx"));
    assert!(core_script.contains("signal=halt exit_status=42 debug_bytes="));
    assert!(core_script.contains("--bin k16"));
    assert!(!core_script.contains("#![no_core]"));
    assert!(!core_script.contains("extern crate alloc"));
    assert!(!core_script.contains("|| true"));
    assert!(!core_script.contains("RUX16_RUSTC"));
    assert!(!core_script.contains("RUX16_LLVM_BIN_DIR"));

    assert!(
        !retired_host_runtime.exists(),
        "K16 host tools must not own guest runtime helper source"
    );
    let helpers =
        fs::read_to_string(&runtime_helpers).expect("K16 guest runtime helper source exists");
    assert!(helpers.contains("#![no_core]"));
    assert!(helpers.contains("#![no_main]"));
    assert!(helpers.contains("__k16_memcpy"));
    assert!(helpers.contains("__k16_memset"));
    assert!(helpers.contains("__k16_memmove"));
    assert!(helpers.contains("fn memcpy("));
    assert!(helpers.contains("fn memset("));
    assert!(helpers.contains("fn memmove("));
    assert!(helpers.contains("__divdi3"));
    assert!(helpers.contains("__udivdi3"));
    assert!(helpers.contains("__moddi3"));
    assert!(helpers.contains("__umoddi3"));
    assert!(helpers.contains("__ashldi3"));
    assert!(helpers.contains("__lshrdi3"));
    assert!(helpers.contains("__ashrdi3"));
    assert!(!helpers.contains("extern crate std"));

    let probe = fs::read_to_string(&bootstrap_probe).expect("Rust bootstrap probe script exists");
    assert!(probe.contains("K16_RUST_SRC"));
    assert!(probe.contains("K16_LLVM_CONFIG"));
    assert!(probe.contains("K16_RUST_BUILD_DIR"));
    assert!(probe.contains("K16_RUST_HOST"));
    assert!(probe.contains("toolchains/Compukter-Kraft-rust"));
    assert!(probe.contains(".toolchain/build/llvm/k16-min/bin/llvm-config"));
    assert!(probe.contains(".toolchain/build/rust/k16"));
    assert!(!probe.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config"));
    assert!(!probe.contains("$RUST_SRC/build/k16"));
    assert!(probe.contains("REQUIRED_LLVM_TOOLS"));
    assert!(probe.contains("llvm-cov"));
    assert!(probe.contains("llvm-nm"));
    assert!(probe.contains("llvm-objcopy"));
    assert!(probe.contains("llvm-profdata"));
    assert!(probe.contains("--targets-built"));
    assert!(probe.contains("--obj-root"));
    assert!(probe.contains("CMakeCache.txt"));
    assert!(probe.contains("LLVM_SOURCE_DIR:STATIC="));
    assert!(probe.contains("src/llvm-project"));
    assert!(probe.contains("ls-tree HEAD src/llvm-project"));
    assert!(probe.contains("merge-base --is-ancestor"));
    assert!(probe.contains("Rust-pinned LLVM commit"));
    assert!(probe.contains("k16"));
    assert!(probe.contains("x.py"));
    assert!(!probe.contains("|| true"));
    assert!(!probe.contains("RUX16_RUST_SRC"));
    assert!(!probe.contains("RUX16_LLVM_CONFIG"));
    assert!(!probe.contains("RUX16_RUST_BUILD_DIR"));
    assert!(!probe.contains("RUX16_RUST_HOST"));

    let llvm_docs = fs::read_to_string(&llvm_docs).expect("LLVM smoke docs exist");
    assert!(llvm_docs.contains("tools/k16-llvm-smoke.sh"));
    assert!(llvm_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(llvm_docs.contains(".toolchain/build/llvm/k16-min/bin"));
    assert!(!llvm_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin"));
    assert!(!llvm_docs.contains("tools/rux16-llvm-smoke.sh"));
    assert!(!llvm_docs.contains("RUX16_LLVM_BIN_DIR"));

    let clang_docs = fs::read_to_string(&clang_docs).expect("Clang smoke docs exist");
    assert!(clang_docs.contains("tools/k16-clang-smoke.sh"));
    assert!(clang_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(clang_docs.contains(".toolchain/build/llvm/k16/bin"));
    assert!(!clang_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16/bin"));
    assert!(!clang_docs.contains("tools/rux16-clang-smoke.sh"));
    assert!(!clang_docs.contains("RUX16_LLVM_BIN_DIR"));

    let llvm_submodule_docs =
        fs::read_to_string(&llvm_submodule_docs).expect("LLVM submodule docs exist");
    assert!(llvm_submodule_docs.contains("K16 LLVM"));
    assert!(!llvm_submodule_docs.contains("Rux16 LLVM Submodule"));

    let docs = fs::read_to_string(&docs).expect("Rust no_core smoke docs exist");
    assert!(docs.contains("tools/k16-rust-nocore-smoke.sh"));
    assert!(docs.contains("tools/k16-unknown-kraftos.json"));
    assert!(docs.contains("K16_RUSTC"));
    assert!(docs.contains("K16_LLVM_BIN_DIR"));
    assert!(docs.contains("custom rustc"));
    assert!(docs.contains("KX"));
    assert!(docs.contains("debug_bytes=2a"));
    assert!(!docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(!docs.contains("RUX16_RUSTC"));
    assert!(!docs.contains("RUX16_LLVM_BIN_DIR"));
    assert!(!docs.contains(".k16e"));

    let core_docs = fs::read_to_string(&core_docs).expect("Rust core smoke docs exist");
    assert!(core_docs.contains("tools/k16-rust-core-smoke.sh"));
    assert!(core_docs.contains("-Z build-std=core"));
    assert!(core_docs.contains("core only"));
    assert!(core_docs.contains("library crate"));
    assert!(core_docs.contains("exported C ABI `main`"));
    assert!(core_docs.contains("RUSTC_BOOTSTRAP=1"));
    assert!(core_docs.contains("-Copt-level=z"));
    assert!(core_docs.contains("-Cdebuginfo=0"));
    assert!(core_docs.contains("no alloc"));
    assert!(core_docs.contains("no std"));
    assert!(core_docs.contains("K16_RUSTC"));
    assert!(core_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(core_docs.contains("signal=halt exit_status=42 debug_bytes="));
    assert!(!core_docs.contains("tools/rux16-rust-core-smoke.sh"));
    assert!(!core_docs.contains("RUX16_RUSTC"));

    let bootstrap_docs = fs::read_to_string(&bootstrap_docs).expect("Rust bootstrap docs exist");
    assert!(bootstrap_docs.contains("tools/k16-rustc-bootstrap-probe.sh"));
    assert!(bootstrap_docs.contains("tools/k16-rust-nocore-smoke.sh"));
    assert!(bootstrap_docs.contains("tools/k16-unknown-kraftos.json"));
    assert!(bootstrap_docs.contains("K16_RUSTC"));
    assert!(bootstrap_docs.contains("K16_LLVM_BIN_DIR"));
    assert!(bootstrap_docs.contains(".toolchain/build/llvm/k16-min/bin/llvm-config"));
    assert!(bootstrap_docs.contains(".toolchain/build/rust/k16"));
    assert!(
        !bootstrap_docs.contains("toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config")
    );
    assert!(!bootstrap_docs.contains("toolchains/Compukter-Kraft-rust/build/k16"));
    assert!(bootstrap_docs.contains("Rust-pinned LLVM commit"));
    assert!(bootstrap_docs.contains("merge-base --is-ancestor"));
    assert!(bootstrap_docs.contains("k16"));
    assert!(!bootstrap_docs.contains("tools/rux16-rustc-bootstrap-probe.sh"));
    assert!(!bootstrap_docs.contains("tools/rux16-rust-nocore-smoke.sh"));
    assert!(!bootstrap_docs.contains("RUX16_RUSTC"));
    assert!(!bootstrap_docs.contains("RUX16_LLVM_BIN_DIR"));

    let feasibility_docs =
        fs::read_to_string(&feasibility_docs).expect("Rust feasibility docs exist");
    let strategy_docs = fs::read_to_string(&strategy_docs).expect("Rust strategy docs exist");
    for docs in [&feasibility_docs, &strategy_docs] {
        assert!(docs.contains("k16 link"));
        assert!(!docs.contains("rux link"));
        assert!(!docs.contains("rux run"));
        assert!(!docs.contains("rux runtime"));
    }
    assert!(feasibility_docs.contains("k16 runtime k16-startup"));
    assert!(feasibility_docs.contains("k16 run"));
}

#[test]
fn llvm_and_rust_backend_sources_use_k16_without_retired_rux16_names() {
    let root = repo_root();
    let backend_paths = [
        "toolchains/Compukter-Kraft-llvm/llvm/include/llvm/TargetParser/Triple.h",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/TargetParser/Triple.cpp",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/CMakeLists.txt",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/TargetInfo/K16TargetInfo.cpp",
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/K16/K16TargetMachine.cpp",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_llvm/build.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_llvm/src/lib.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/mod.rs",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/k16.rs",
    ];

    for path in backend_paths {
        let contents = fs::read_to_string(root.join(path))
            .unwrap_or_else(|error| panic!("expected active K16 backend file `{path}`: {error}"));
        assert!(
            !contents.contains("rux16")
                && !contents.contains("Rux16")
                && !contents.contains("RUX16"),
            "active K16 backend file `{path}` should not keep retired Rux16 names"
        );
    }

    let retired_paths = [
        "toolchains/Compukter-Kraft-llvm/llvm/lib/Target/Rux16",
        "toolchains/Compukter-Kraft-rust/compiler/rustc_target/src/callconv/rux16.rs",
    ];
    for path in retired_paths {
        assert!(
            !root.join(path).exists(),
            "retired backend path `{path}` should not exist"
        );
    }
}

#[test]
fn active_k16_tools_do_not_ship_rux_compiler_surface() {
    let root = repo_root();
    let retirement_audit =
        fs::read_to_string(root.join("docs/toolchains/rux-language-retirement-audit.md"))
            .expect("Rux language retirement audit exists");

    for path in [
        "rux",
        "rust/host/k16-tools/src/bin/rux.rs",
        "rust/host/k16-tools/src/advice.rs",
        "rust/host/k16-tools/src/frontend",
        "rust/host/k16-tools/src/runtime",
        "rust/host/k16-tools/stdlib",
        "rust/host/k16-tools/examples",
        "rust/host/k16-tools/tests/k16_artifact_backend.rs",
        "rust/host/k16-tools/tests/rux_check_cli.rs",
        "rust/host/k16-tools/tests/rux_compile_cli.rs",
        "rust/host/k16-tools/tests/rux_compiler_runtime_surface.rs",
        "rust/host/k16-tools/tests/rux_public_cli_surface.rs",
    ] {
        assert!(
            !root.join(path).exists(),
            "active K16 tools must not ship retired Rux compiler path `{path}`"
        );
    }

    assert!(retirement_audit.contains(
        "[#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):\n  Rux stdlib and source advice have been removed from active tooling."
    ));
    assert!(!retirement_audit.contains(
        "[#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):\n  Remove Rux stdlib and source advice."
    ));
}

fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
        .expect("rust/host/k16-tools has repo root great-grandparent")
}

fn rust_guest_workspace_members(manifest: &str) -> Vec<&str> {
    manifest
        .lines()
        .map(str::trim)
        .filter_map(|line| {
            line.strip_prefix('"')
                .and_then(|line| line.split_once('"'))
                .map(|(member, _)| member)
        })
        .collect()
}
