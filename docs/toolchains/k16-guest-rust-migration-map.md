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
- `guest/c/init` contains the production C init launcher.
- `guest/c/shell` contains the production C shell.
- `guest/c/coreutils` contains production C coreutils.

## Migration Roles

| Crate | Current role | C-first disposition |
| --- | --- | --- |
| `rust/guest/k16-abi` | Shared K16 guest ABI definitions used by Rust guest code. | Keep Rust while Rust kernel/runtime crates exist. |
| `rust/guest/k16-alloc-test` | Former development heap/syscall smoke utility. | Removed with the Rust userland proof layer. |
| `rust/guest/k16-bios` | Former Rust BIOS firmware. | Removed; bundled BIOS now builds from `guest/c/bios/bios.c`. |
| `rust/guest/k16-boot` | Former Rust system boot program. | Removed; bundled bootloader now builds from `guest/c/boot/boot.c` and `guest/c/boot-chain`. |
| `rust/guest/k16-boot-chain` | Former Rust boot-chain support library. | Removed; remaining Rust helper code now lives inside `rust/guest/k16-kernel/src/boot_chain.rs`, while C firmware uses the separate `guest/c/boot-chain` helper. |
| `rust/guest/k16-image` | Former guest-side K16E image parser library. | Removed; remaining Rust image parser code now lives inside `rust/guest/k16-kernel/src/image.rs` as kernel-owned code. |
| `rust/guest/k16-init` | Former Rust production init process. | Legacy/removable after no focused tests/docs need the old Rust launcher; production `/bin/init.kx` now builds from `guest/c/init/init.c`. |
| `rust/guest/k16-kernel` | Rust K16 kernel and OS internals. | Keep Rust. Kernel rewrite is explicitly out of scope for #367. |
| `rust/guest/k16-memory` | Guest memory/MMU helper library. | Keep Rust while kernel/runtime memory management remains Rust. |
| `rust/guest/k16-proc-test` | Former development process-model smoke utility. | Removed with the Rust userland proof layer. |
| `rust/guest/k16-rt` | Low-level Rust guest runtime/syscall/trap helper crate. | Keep Rust for Rust kernel/boot/runtime consumers; do not expand as userland std. |
| `rust/guest/k16-runtime-import-test` | Former development dynamic-import smoke program for `libk16rt.kso`. | Removed with the Rust shared-runtime proof. |
| `rust/guest/k16-shared-kraft` | Former Rust provider for the shared OS ABI library `libkraft.kso`. | Removed; `/lib/libkraft.kso` now builds from `guest/c/libkraft/libkraft.c`. |
| `rust/guest/k16-shared-runtime` | Former Rust provider for `libk16rt.kso` memory helpers. | Removed; production userland now ships only the C `libkraft.kso` shared library. |
| `rust/guest/k16-shell` | Former Rust production shell. | Legacy/removable after no focused tests/docs need the old Rust shell; production `/bin/shell.kx` now builds from `guest/c/shell/shell.c`. |
| `rust/guest/k16-storage` | Former guest storage/filesystem support library. | Removed; remaining Rust K16PT/K16FS storage helper code now lives inside `rust/guest/k16-kernel/src/storage.rs` as kernel-owned code. |
| `rust/guest/k16-syscall-fault-test` | Former development syscall fault smoke utility. | Removed with the Rust userland proof layer. |
| `rust/guest/k16-user-fault-test` | Former development user fault smoke utility. | Removed with the Rust userland proof layer. |
| `rust/guest/kraft-std` | Former Rust userland convenience layer over K16 syscalls. | Removed; C libc-lite is the active userland API layer. |

## Removed Rust Userland Crates

The former Rust production init launcher `k16-init` has been replaced in the
production image by `guest/c/init/init.c`. The old Rust crate remains in the
workspace until the deletion rule below is satisfied.

The former Rust production shell `k16-shell` has been replaced in the
production image by `guest/c/shell/shell.c`. The C shell preserves the shipped
prompt, builtins, cwd-aware path handling, `RUN` argv dispatch, and fd-backed
stdin/stdout behavior while removing the production Rust `core,alloc` shell
build.

The former Rust production coreutils `k16-uname`, `k16-cat`, `k16-write`,
`k16-rm`, `k16-mkdir`, `k16-rmdir`, `k16-stat`, `k16-ls`, `k16-cp`, and
`k16-mv` have been removed from the guest workspace after their production
artifacts moved to `guest/c/coreutils`. The bundled MOTD data now lives at
`guest/data/etc/motd`, outside any Rust utility crate.

The hosted Rust proof crates `k16-hosted-cat` and `k16-hosted-hello` were also
removed from the guest workspace. Hosted Rust std is no longer an active
userland proof path.

The former Rust shared OS ABI provider `k16-shared-kraft` was removed from the
guest workspace after `/lib/libkraft.kso` moved to
`guest/c/libkraft/libkraft.c`.

The Rust shared-runtime proof crates `k16-shared-runtime` and
`k16-runtime-import-test` were removed from the guest workspace. The production
and development storage layouts no longer ship `/lib/libk16rt.kso` or
`/bin/runtime-import-test.kx`; shared userland code is represented by the C
`libkraft.kso` provider.

The remaining Rust userland proof crates `k16-alloc-test`, `k16-proc-test`,
`k16-syscall-fault-test`, `k16-user-fault-test`, and `kraft-std` were removed
from the guest workspace. The development storage resource remains available
for tests but no longer installs Rust userland proof binaries.

## Next Production C Candidates

No production Rust coreutils/shell crate remains in the bundled image. Further
C-first cleanup should follow the deletion rule for replaced Rust crates or
move shared ABI providers after the current production surface is stable.

## Development/test-only

There are currently no Rust development/test-only userland crates in the guest
workspace.

## Deletion Rule

Do not delete a Rust guest crate merely because a C replacement exists. Delete
or remove it from the workspace only after all of these are true:

- production Gradle tasks no longer read its manifest/source/data files;
- focused Kotlin and Rust host-tool tests no longer assert behavior through it;
- docs no longer present it as an active production path;
- replacement C coverage proves the same shipped behavior.
