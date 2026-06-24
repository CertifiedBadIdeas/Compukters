# kraft-std Guest Library

Issue: [#192](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/192), [#194](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/194), [#195](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/195), [#230](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/230), [#232](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/232), [#247](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/247), [#248](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/248), [#249](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/249), [#295](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/295), [#296](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/296), [#297](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/297), [#333](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/333), [#336](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/336), [#337](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/337)

`rust/guest/kraft-std` is the experimental KraftOS userland library boundary for
guest Rust programs. It is intentionally separate from the lower-level K16
crates:

| Crate | Responsibility |
| --- | --- |
| `k16-abi` | Numeric contracts: MMIO addresses, CSR numbers, interrupt sources, syscall IDs. |
| `k16-rt` | Target/runtime glue: CPU helper symbols, raw syscall helpers, trap/interrupt helpers, MMIO-backed halt/yield/timer helpers. |
| `kraft-std` | Userland-facing KraftOS APIs layered on the runtime. |

The initial `kraft-std` surface is deliberately small:

| API | Backing runtime call | Meaning |
| --- | --- | --- |
| `kraft_std::debug::marker()` | `k16_rt::debug_marker()` | Calls the current debug marker proof syscall and returns the kernel result. |
| `kraft_std::debug::write_byte(byte)` | `k16_rt::debug_write_byte(byte)` | Calls the current debug byte proof syscall and returns the kernel status. |
| `kraft_std::io::stdin().read(bytes)` | `k16_rt::read_syscall(FD_STDIN, ptr, len)` | Reads bytes from stdin through the kernel fd ABI. |
| `kraft_std::io::stdout().write_all(bytes)` | `k16_rt::write_syscall(FD_STDOUT, ptr, len)` | Writes a byte slice to stdout through the kernel fd ABI. |
| `kraft_std::io::stderr().write_all(bytes)` | `k16_rt::write_syscall(FD_STDERR, ptr, len)` | Writes a byte slice to stderr through the kernel fd ABI. |
| `kraft_std::fs::open(path)` | `k16_rt::open_syscall(path, len, OPEN_READ_ONLY)` | Opens an absolute read-only ROOT/K16FS file path and returns a regular file descriptor. |
| `kraft_std::fs::create(path)` | `k16_rt::open_syscall(path, len, OPEN_WRITE_ONLY \| OPEN_CREATE \| OPEN_TRUNCATE)` | Creates or truncates an absolute ROOT/K16FS regular file path and returns a write-only regular file descriptor. |
| `kraft_std::fs::append(path)` | `k16_rt::open_syscall(path, len, OPEN_WRITE_ONLY \| OPEN_CREATE \| OPEN_APPEND)` | Creates a missing absolute ROOT/K16FS regular file or opens an existing one with its descriptor offset at EOF. |
| `kraft_std::fs::File::read(bytes)` | `k16_rt::read_syscall(fd, ptr, len)` | Reads bytes from a regular file descriptor owned by the current foreground process and advances its descriptor offset. |
| `kraft_std::fs::File::write_all(bytes)` | `k16_rt::write_syscall(fd, ptr, len)` | Writes a byte slice to a write-only regular file descriptor owned by the current foreground process and advances its descriptor offset. |
| `kraft_std::fs::File::seek_start(offset)` | `k16_rt::seek_syscall(fd, offset, SEEK_SET)` | Sets a regular file descriptor offset inside the current file size and returns the new offset. |
| `kraft_std::fs::File::seek_end()` | `k16_rt::seek_syscall(fd, 0, SEEK_END)` | Sets a regular file descriptor offset to EOF and returns the file size. |
| `kraft_std::fs::File::close()` | `k16_rt::close_syscall(fd)` | Releases an open regular file descriptor owned by the current foreground process. |
| `kraft_std::fs::remove_file(path)` | `k16_rt::unlink_syscall(path, len)` | Removes an absolute ROOT/K16FS regular file path. Directories and files with open kernel fds are rejected by the kernel. |
| `kraft_std::fs::create_dir(path)` | `k16_rt::mkdir_syscall(path, len)` | Creates an absolute ROOT/K16FS directory path. Parent directories must already exist. |
| `kraft_std::fs::remove_dir(path)` | `k16_rt::rmdir_syscall(path, len)` | Removes an empty absolute ROOT/K16FS directory path. Non-empty directories are rejected by the kernel. |
| `kraft_std::heap::brk(address)` | `k16_rt::brk_syscall(address)` | Sets the current foreground process program break and returns the resulting break. |
| `kraft_std::heap::sbrk(delta)` | `k16_rt::sbrk_syscall(delta)` | Grows the current foreground process program break and returns the previous break. |
| `kraft_std::heap::SbrkAllocator` | `BRK`/`SBRK` syscall ABI | Guest global allocator used by `alloc` collections. Allocation is monotonic; deallocation is currently a no-op. |
| `kraft_std::process::exit(status)` | `k16_rt::exit_syscall(status)` | Terminates the current foreground process through the kernel. |
| `kraft_std::process::run(path)` | `k16_rt::run_syscall(path, len)` | Runs a dynamic `/bin/*.kx` foreground child program with no arguments and returns `process::ExitStatus` on successful launch. |
| `kraft_std::process::run_with_args(path, args)` | `k16_rt::run_argv_syscall(request, len)` | Runs a dynamic foreground child program with `1..=4` bounded argv byte strings and returns `process::ExitStatus` on successful launch. |
| `kraft_std::process::spawn_with_args(path, args)` | `k16_rt::spawn_argv_syscall(request, len)` | Creates a ready direct child with `1..=4` bounded argv byte strings and returns `process::ProcessId` without entering it. |
| `kraft_std::process::wait(pid)` | `k16_rt::wait_syscall(pid, out_status)` | Enters a ready direct child, waits for exit, and returns `process::WaitStatus` with the child PID and exit status. |
| `kraft_std::process::wait_any()` | `k16_rt::wait_syscall(0, out_status)` | Waits for any ready direct child and returns the reaped child PID plus exit status. |
| `kraft_std::process::ExitStatus` | K16 `RUN` non-negative return | Wraps a child exit code. `code()` returns the raw child status and `success()` is true only for `0`. |
| `kraft_std::process::ProcessId` / `WaitStatus` | K16 `SPAWN` / `WAIT` non-negative returns | Wrap child PIDs and waited child statuses for the split process lifecycle. |
| `kraft_std::process::Argv::from_raw(argc, argv)` | K16 child-entry `r1`/`r2` argv ABI | Reads the argv table installed by the kernel for argv-aware child programs. |
| `kraft_std::thread::yield_now()` | `k16_rt::yield_syscall()` | Requests one OS-level yield through the kernel syscall path. |
| `kraft_std::thread::sleep_ticks(ticks)` | `k16_rt::sleep_ticks_syscall(ticks)` | Requests a timer0 game-tick sleep through the kernel syscall path. |
| `kraft_std::prelude` | n/a | Re-exports the early userland modules intended for guest programs. |

`debug::*` is diagnostic. Normal user output should go through `io` and the
kernel fd syscall ABI, not debug MMIO.

`kraft-std` is `#![no_std]`. It is not Rust's hosted `std`, a POSIX layer, or a
complete OS API. The accepted long-term direction is a shared
[K16 hosted ABI v0](../superpowers/specs/2026-06-21/issue-332-k16-hosted-abi-v0.md)
under Rust `std`, libc, libc++, and KraftOS-specific extensions. In that model,
`kraft-std` remains the current incubator for userland API decisions and the
future extension crate for K16/KraftOS APIs that do not belong in language
standard libraries.

The first hosted Rust stages are narrow proofs, not a replacement for
`kraft-std`. `rust/guest/k16-hosted-hello` uses ordinary Rust `std`, ordinary
`fn main()`, stdout, panic-abort, and heap-backed `Vec`/`String` formatting, and
is built by the `:v1_21_1-neoforge:compileK16HostedHello` Gradle task. Hosted
Rust heap allocation goes through the KraftOS `SBRK` syscall ABI and remains
monotonic for now. Hosted Rust `std::env::args()` reads the existing K16 child
entry ABI: startup preserves `r1 = argc` and `r2 = argv_table`, and each argv
table entry is a `(ptr, len)` pair of little-endian `u32` words. Invalid argv
ABI state is a runtime error, not a silent empty-args fallback. Hosted Rust
`std::fs` has a first read-only stage backed by KraftOS `OPEN`, `READ`, and
`CLOSE`: `File::open`, `File::read`, close-on-drop, and
`std::fs::read_to_string` work for regular ROOT/K16FS file reads. Metadata,
directories, create/write/truncate/append, seek, remove/rename, permissions,
symlinks, canonicalize, and cwd remain explicit unsupported operations in
hosted `std`. Broader hosted `std` coverage remains gated on explicit OS-backed
APIs rather than silent no-op platform behavior.
KraftOS-backed Rust `std` hooks live in named `sys/*/kraftos.rs` modules rather
than generic `unsupported.rs` stubs when the API is actually OS-backed.

The first production C hosted utility is deliberately separate from Rust
`std`. `rust/guest/c/coreutils/cat.c` is compiled by K16 Clang and linked as
`/bin/cat.kx` against `libkraft.k16so`. Its libc-lite startup layer under
`rust/guest/c/libc` adapts the K16 bounded argv table into ordinary C
`main(int argc, char **argv)` and keeps kernel/runtime code on the existing
Rust `no_std` path.

The current filesystem surface is a small ROOT/K16FS proof for absolute paths:
read-only opens, create/truncate write-only opens, whole-slice writes within
preallocated file extents, directory creation/removal, directory listing, and
metadata.
Regular file descriptors are process-owned and are not inherited across
`process::run`. The current allocator surface is a monotonic `SBRK`-backed
guest allocator for foreground userland programs. Append, seek, multi-extent
growth, background process/task management, allocator reuse, and full POSIX
compatibility are separate future slices.

The bundled init shell uses this allocator for its editable input line. When
allocation is exhausted, printable input that cannot be stored is not echoed and
is not included in the command buffer.

## Layering Rule

The dependency direction is one-way: `kraft-std` may depend on `k16-rt`, but
`k16-rt` must not depend on `kraft-std`.

`k16-rt` is the lower-level runtime layer. It owns CPU helper symbols, raw
syscall helpers, trap/interrupt helpers, and MMIO-backed target operations.
Keeping it below `kraft-std` lets BIOS, bootloader, kernel, and low-level guest
programs use the runtime without pulling in userland APIs.

`kraft-std` is the higher-level userland layer. It should expose KraftOS-facing
APIs with OS-level names and semantics. It must not expose `pub use k16_rt::*`;
that would collapse the boundary and make runtime internals part of the
standard-library surface.

Host tests for crates layered on `k16-rt` use the explicit `k16-rt/host-test`
feature. That feature enables runtime stubs and `k16_rt::host_test` observation
helpers for tests only; it is not a guest fallback path.

The fd methods are normal cross-crate methods, not an inline-only ABI escape
hatch. `rust/host/k16-tools` keeps a K16 Result-return smoke that links a
temporary user program against `kraft-std` through `k16-ld` and verifies
`io::stdout().write_all(...)` plus `io::stdin().read(...)` across that crate
boundary.
