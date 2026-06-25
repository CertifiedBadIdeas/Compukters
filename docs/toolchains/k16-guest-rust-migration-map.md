# K16 Guest Rust Migration Map

> Issue: [#369](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/369)
>
> Parent direction: [#367](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/367)

## Policy

The active direction is a C-first userland/coreutils policy. New production
KraftOS userland programs should prefer `guest/c` and the libc-lite surface
unless a slice explicitly needs Rust to validate the compiler/toolchain path.

Rust kernel remains Rust for now. The C-first policy does not rewrite the K16
kernel, boot chain, low-level runtime crates, host VM, host tools, LLVM backend,
or Minecraft integration. Those boundaries are separate decisions.

The immediate migration target is the shipped userland/coreutils surface:
production commands should move toward C one slice at a time, with Gradle,
firmware, ABI docs, and runtime tests updated alongside each move. Rust crates
that are already replaced by C should become legacy/removable only after no
production build or focused test needs them.

## Current C Guest Root

- `guest/c/libc` contains libc-lite startup, syscall wrappers, and public
  standard-shaped headers.
- `guest/c/coreutils` contains production C coreutils.

## Migration Roles

| Crate | Current role | C-first disposition |
| --- | --- | --- |
| `rust/guest/k16-abi` | Shared K16 guest ABI definitions used by Rust guest code. | Keep Rust while Rust kernel/runtime crates exist. |
| `rust/guest/k16-alloc-test` | Development heap/syscall smoke utility. | Development/test-only; keep until equivalent coverage moves elsewhere. |
| `rust/guest/k16-bios` | BIOS firmware. | Keep Rust for now; not part of userland/coreutils migration. |
| `rust/guest/k16-boot` | System boot program. | Keep Rust for now; boot flow is outside this userland slice. |
| `rust/guest/k16-boot-chain` | Shared boot-chain support library. | Keep Rust for now; boot support is outside this userland slice. |
| `rust/guest/k16-cat` | Former Rust production `cat` plus `/etc/motd` source data. | Legacy/removable after the `motd.txt` data dependency is moved out or retired. |
| `rust/guest/k16-cp` | Production Rust `cp`. | Migrate to C after libc-lite has enough file-copy helpers. |
| `rust/guest/k16-hosted-cat` | Hosted Rust std proof program. | Development/toolchain proof only; not a production userland direction. |
| `rust/guest/k16-hosted-hello` | Hosted Rust std proof program. | Development/toolchain proof only; not a production userland direction. |
| `rust/guest/k16-image` | Guest-side image/storage support used by Rust guest code. | Keep Rust while Rust boot/kernel/storage support remains Rust. |
| `rust/guest/k16-init` | Production init process. | Migrate to C after shell/exec expectations are stable enough for a tiny init proxy. |
| `rust/guest/k16-kernel` | Rust K16 kernel and OS internals. | Keep Rust. Kernel rewrite is explicitly out of scope for #367. |
| `rust/guest/k16-ls` | Production Rust `ls`. | Migrate to C after libc-lite exposes directory/listing or metadata wrappers. |
| `rust/guest/k16-memory` | Guest memory/MMU helper library. | Keep Rust while kernel/runtime memory management remains Rust. |
| `rust/guest/k16-mkdir` | Production Rust `mkdir`. | Migrate to C after libc-lite exposes a `mkdir` wrapper and status mapping. |
| `rust/guest/k16-mv` | Production Rust `mv`. | Migrate to C after rename/link/unlink semantics are stable in libc-lite. |
| `rust/guest/k16-proc-test` | Development process-model smoke utility. | Development/test-only; keep until process coverage moves elsewhere. |
| `rust/guest/k16-rm` | Production Rust `rm`. | Migrate to C after libc-lite exposes an `unlink` wrapper and error policy. |
| `rust/guest/k16-rmdir` | Production Rust `rmdir`. | Migrate to C after libc-lite exposes an `rmdir` wrapper and error policy. |
| `rust/guest/k16-rt` | Low-level Rust guest runtime/syscall/trap helper crate. | Keep Rust for Rust kernel/boot/runtime consumers; do not expand as userland std. |
| `rust/guest/k16-runtime-import-test` | Development dynamic-import smoke program. | Development/test-only; keep while it covers loader/import behavior. |
| `rust/guest/k16-shared-kraft` | Rust provider for the shared OS ABI library `libkraft.k16so`. | Keep Rust temporarily; revisit after C userland/coreutils migration proves the ABI surface. |
| `rust/guest/k16-shared-runtime` | Rust provider for shared runtime helpers. | Keep Rust temporarily; runtime helper sharing is separate from coreutils migration. |
| `rust/guest/k16-shell` | Production shell. | Migrate to C after enough libc-lite process/path helpers exist. |
| `rust/guest/k16-stat` | Production Rust `stat`. | Migrate to C after libc-lite exposes a stable metadata wrapper and struct layout. |
| `rust/guest/k16-storage` | Guest storage/filesystem support library. | Keep Rust while kernel/storage internals remain Rust. |
| `rust/guest/k16-syscall-fault-test` | Development syscall fault smoke utility. | Development/test-only; keep while it covers fault policy. |
| `rust/guest/k16-uname` | Former Rust production `uname`. | Legacy/removable after no focused tests or docs depend on the Rust version. Production `/bin/uname.kx` now builds from `guest/c/coreutils/uname.c`. |
| `rust/guest/k16-user-fault-test` | Development user fault smoke utility. | Development/test-only; keep while it covers user fault policy. |
| `rust/guest/k16-write` | Former Rust production `write`. | Legacy/removable after no focused tests or docs depend on the Rust version. |
| `rust/guest/kraft-std` | Project Rust userland convenience layer over K16 syscalls. | Freeze for production direction; keep for tests/legacy until C libc-lite replaces required userland APIs. |

## Next Production C Candidates

1. `rust/guest/k16-mkdir`, `rust/guest/k16-rmdir`, and `rust/guest/k16-rm`:
   small filesystem mutators once libc-lite has wrappers for their syscalls.
2. `rust/guest/k16-stat` and `rust/guest/k16-ls`: metadata/listing commands
   after the metadata ABI is documented for C.
3. `rust/guest/k16-cp` and `rust/guest/k16-mv`: larger file operations after
   path and file-copy helper policy is settled.
4. `rust/guest/k16-shell` and `rust/guest/k16-init`: move after enough
   process/path helpers exist to avoid embedding policy in each program.

## Development/test-only

These crates are not production userland targets. They remain useful while they
cover behavior that the production image or host tools do not yet verify:

- `rust/guest/k16-alloc-test`
- `rust/guest/k16-hosted-cat`
- `rust/guest/k16-hosted-hello`
- `rust/guest/k16-proc-test`
- `rust/guest/k16-runtime-import-test`
- `rust/guest/k16-syscall-fault-test`
- `rust/guest/k16-user-fault-test`

## Deletion Rule

Do not delete a Rust guest crate merely because a C replacement exists. Delete
or remove it from the workspace only after all of these are true:

- production Gradle tasks no longer read its manifest/source/data files;
- focused Kotlin and Rust host-tool tests no longer assert behavior through it;
- docs no longer present it as an active production path;
- replacement C coverage proves the same shipped behavior.
