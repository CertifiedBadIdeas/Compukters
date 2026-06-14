# kraft-std Guest Library

Issue: [#192](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/192), [#194](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/194), [#195](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/195), [#230](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/230), [#232](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/232), [#247](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/247), [#248](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/248), [#249](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/249)

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
| `kraft_std::fs::open(path)` | `k16_rt::open_syscall(path, len, 0)` | Opens an absolute read-only ROOT/K16FS file path and returns a regular file descriptor. |
| `kraft_std::fs::File::read(bytes)` | `k16_rt::read_syscall(fd, ptr, len)` | Reads bytes from a regular file descriptor owned by the current foreground process and advances its descriptor offset. |
| `kraft_std::fs::File::close()` | `k16_rt::close_syscall(fd)` | Releases an open regular file descriptor owned by the current foreground process. |
| `kraft_std::heap::brk(address)` | `k16_rt::brk_syscall(address)` | Sets the current foreground process program break and returns the resulting break. |
| `kraft_std::heap::sbrk(delta)` | `k16_rt::sbrk_syscall(delta)` | Grows the current foreground process program break and returns the previous break. |
| `kraft_std::heap::SbrkAllocator` | `BRK`/`SBRK` syscall ABI | Guest global allocator used by `alloc` collections. Allocation is monotonic; deallocation is currently a no-op. |
| `kraft_std::process::exit(status)` | `k16_rt::exit_syscall(status)` | Terminates the current foreground process through the kernel. |
| `kraft_std::process::run(path)` | `k16_rt::run_syscall(path, len)` | Runs a dynamic `/bin/*.kx` foreground child program with no arguments. |
| `kraft_std::process::run_with_args(path, args)` | `k16_rt::run_argv_syscall(request, len)` | Runs a dynamic foreground child program with one bounded argv byte string. |
| `kraft_std::process::Argv::from_raw(argc, argv)` | K16 child-entry `r1`/`r2` argv ABI | Reads the argv table installed by the kernel for argv-aware child programs. |
| `kraft_std::thread::yield_now()` | `k16_rt::yield_syscall()` | Requests one OS-level yield through the kernel syscall path. |
| `kraft_std::thread::sleep_ticks(ticks)` | `k16_rt::sleep_ticks_syscall(ticks)` | Requests a timer0 game-tick sleep through the kernel syscall path. |
| `kraft_std::prelude` | n/a | Re-exports the early userland modules intended for guest programs. |

`debug::*` is diagnostic. Normal user output should go through `io` and the
kernel fd syscall ABI, not debug MMIO.

`kraft-std` is `#![no_std]`. It is not Rust's hosted `std`, a POSIX layer, or a
complete OS API. The current filesystem surface is a read-only ROOT/K16FS proof
for absolute paths; regular file descriptors are process-owned and are not
inherited across `process::run`. The current allocator surface is a monotonic `SBRK`-backed
guest allocator for foreground userland programs. Directory iteration, writable
files, background process/task management, allocator reuse, and full POSIX
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
