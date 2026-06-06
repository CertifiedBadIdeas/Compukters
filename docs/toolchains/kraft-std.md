# kraft-std Guest Library

Issue: [#192](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/192)

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
| `kraft_std::prelude` | n/a | Re-exports the early userland modules intended for guest programs. |

`kraft-std` is `#![no_std]`. It is not Rust's hosted `std`, a POSIX layer, or a
complete OS API. Allocator support, files, input, sleep/yield, process/task
management, and a stable error model are separate future slices.

Host tests for crates layered on `k16-rt` use the explicit `k16-rt/host-test`
feature. That feature enables runtime stubs and `k16_rt::host_test` observation
helpers for tests only; it is not a guest fallback path.
