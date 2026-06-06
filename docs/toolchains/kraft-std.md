# kraft-std Guest Library

Issue: [#192](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/192), [#194](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/194)

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
| `kraft_std::thread::yield_now()` | `k16_rt::yield_syscall()` | Requests one OS-level yield through the kernel syscall path. |
| `kraft_std::prelude` | n/a | Re-exports the early userland modules intended for guest programs. |

`kraft-std` is `#![no_std]`. It is not Rust's hosted `std`, a POSIX layer, or a
complete OS API. Allocator support, files, input, sleep, process/task
management, and a stable error model are separate future slices.

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
