# K16 Source Layout

> Issue: [#398](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/398)

K16 sources are organized by ownership role, not by implementation language.

## Roots

- `guest/firmware` contains code that starts or loads KraftOS, but is not part
  of the OS runtime itself.
- `guest/platform/k16` contains K16-specific low-level helper sources that are
  shared by guest artifacts.
- `guest/kraftos` contains the KraftOS workspace: ABI definitions, kernel,
  runtime support, libc-lite, shared libraries, userland programs, and bundled
  data.
- `host` contains host-side VM and tooling crates.

## KraftOS Tree

- `guest/kraftos/abi` contains Rust ABI definitions used by Rust guest code.
- `guest/kraftos/kernel` contains the Rust K16 kernel.
- `guest/kraftos/runtime` contains low-level Rust runtime/syscall/trap helpers.
- `guest/kraftos/libc` contains the C libc-lite startup, syscall wrappers, and
  public headers.
- `guest/kraftos/lib` contains shared userland libraries such as `libkraft`.
- `guest/kraftos/userland` contains shipped programs such as init, shell, and
  coreutils.
- `guest/kraftos/data` contains bundled KraftOS data files such as `/etc/motd`.

The top-level path should answer which part of the guest system owns the code.
Language-specific directory roots such as the old `rust/guest` and `rust/host`
trees should not be reintroduced.
