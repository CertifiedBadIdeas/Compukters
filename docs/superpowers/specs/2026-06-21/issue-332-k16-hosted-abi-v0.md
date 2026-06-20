# K16 Hosted ABI V0

> Issue: [#332](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/332)

## Context

K16 userland has moved past isolated freestanding experiments. The current
system boots through BIOS and bootloader code, runs a Rust kernel, launches
translated `/bin/init.kx`, supervises `/bin/shell.kx`, and executes bundled
utilities through a bounded process, filesystem, argv, and heap ABI.

`kraft-std` is useful as the current userland-facing API, but it is still a
`#![no_std]` crate layered over raw K16 syscalls. Every user program still owns
too much runtime ceremony: `#![no_std]`, `#![no_main]`, an unmangled C ABI
entrypoint, and a panic handler. That is acceptable for BIOS, bootloader, and
kernel code. It should not be the long-term authoring model for ordinary
KraftOS programs.

The accepted direction is to treat Rust `std`, libc, and libc++ as consumers of
one shared hosted K16/KraftOS ABI instead of building separate runtime worlds.
`kraft-std` remains valuable, but its role changes: it is the incubator for
KraftOS-specific API decisions and the future extension crate for APIs that do
not belong in Rust `std` or libc.

## Decision

K16 should define a first-class hosted ABI v0.

This ABI is the lower platform abstraction consumed by:

- Rust partial `std` for K16;
- libc for K16;
- libc++ and libc++abi for K16;
- `kraft-std` KraftOS-specific extensions.

The hosted ABI must be strict. Supported operations must perform real guest
work through K16 syscalls or runtime primitives. Unsupported operations must
fail explicitly with an unsupported error, abort, or panic at a clearly defined
boundary. They must not pretend to succeed as no-ops.

The near-term goal is not full POSIX or a complete Rust `std`. The goal is a
small, honest hosted layer that lets ordinary programs use normal language
entrypoints and a subset of standard library APIs without hiding missing OS
semantics.

## Layering

The target layering is:

```text
K16 CPU, MMIO, storage, and syscall ABI
    ↓
K16 hosted ABI / platform abstraction layer
    ↓                  ↓                 ↓
Rust std::sys::k16     libc              kraft-std extensions
                       ↓
                    libc++
```

The hosted ABI owns language-neutral runtime contracts. Rust `std`, libc, and
libc++ should not independently invent different meanings for argv, errno,
file descriptors, paths, heap growth, or clocks.

`kraft-std` should continue to expose KraftOS-specific APIs such as raw K16
statuses, process helpers that do not map cleanly to libc, debug syscalls,
MMIO-facing helpers, shell/coreutils policy, and other explicit OS extensions.
It should not be the only way to write normal user programs.

## Hosted ABI V0 Surface

The v0 hosted ABI should define these contracts before any full sysroot port is
attempted:

- process startup and exit;
- argc/argv layout and optional environment layout;
- fd numbers and ownership;
- stdin/stdout/stderr behavior;
- open/read/write/close/seek/stat/read-dir semantics;
- current working directory and path rules;
- K16 negative status to `errno` / language error mapping;
- heap growth through `BRK`/`SBRK`;
- monotonic and wall-clock baseline, if wall-clock exists at all;
- unsupported-feature policy.

The v0 ABI may intentionally exclude many hosted APIs. Exclusions must be
observable as unsupported, not silent success.

## Process Startup

The hosted startup model should allow ordinary Rust, C, and C++ programs to
define normal entrypoints:

```rust
fn main() {
    println!("hello");
}
```

```c
int main(int argc, char **argv) {
    puts("hello");
    return 0;
}
```

The low-level runtime remains explicit internally. A K16 `crt0` or equivalent
startup object should:

1. receive or reconstruct the K16 process entry state;
2. build `argc`, `argv`, and optional `envp`;
3. initialize language runtime state required by the selected sysroot;
4. call the language entrypoint;
5. convert the return value into `EXIT(status)`.

The current `k16-startup -> main` object boundary is a useful proof, but hosted
Rust `std` also needs Rust runtime startup such as `lang_start` or an
equivalent target-specific integration.

## Errors

K16 syscalls return negative status values encoded as high-bit-set `u32`
values. Hosted code needs language-standard error surfaces:

- libc exposes `errno`;
- Rust exposes `std::io::Error` and `ErrorKind`;
- C++ exposes libc/libc++ errors on top of libc.

The hosted ABI should define one canonical mapping table. Existing K16 statuses
should be reused where possible:

- missing file or executable: `ENOENT` / `NotFound`;
- invalid argument, malformed path, invalid PID: `EINVAL` / `InvalidInput`;
- bad fd: `EBADF`;
- busy process or resource: `EBUSY`;
- no memory or no process slot: `ENOMEM`;
- pointer fault or invalid executable image: `EFAULT` or a documented nearest
  hosted error;
- unsupported hosted API: `ENOSYS` or `EOPNOTSUPP`, depending on the API.

The mapping should be stable enough that `kraft-std`, Rust `std`, and libc do
not disagree about the same kernel status.

## File Descriptors And Filesystem

The hosted fd model should start from the current K16 fd ABI:

- `0`: stdin;
- `1`: stdout;
- `2`: stderr;
- `>=3`: regular process-owned file descriptors.

V0 should support the already-proven subset: open, create/truncate, append,
read, write, close, seek, metadata/stat, directory creation/removal, unlink,
rename, and directory listing over ROOT/K16FS paths.

The initial fd inheritance model can stay conservative. Current process work
does not yet define general fd inheritance or stdio remapping. Hosted ABI v0
should document this as a limitation and avoid pretending that POSIX `fork`,
`execve`, pipes, or inherited descriptor tables exist.

## Paths And CWD

The current shell and `kraft-std::path` work establish a bounded UTF-8 path
model with a working directory. Hosted ABI v0 should keep paths bounded and
explicit rather than adopting unlimited POSIX path assumptions.

The path contract should specify:

- maximum byte length;
- UTF-8 requirement, if retained;
- absolute and relative resolution;
- root filesystem namespace;
- `.` and `..` normalization;
- current directory storage per process.

Rust `std::path`, libc path functions, and `kraft-std::path` should all follow
the same rules.

## Heap And Allocation

The current guest allocator is backed by `BRK`/`SBRK` and is monotonic.
Deallocation is currently a no-op in `kraft-std::heap::SbrkAllocator`.

Hosted ABI v0 may accept monotonic heap growth as an explicit early policy, but
it must state that reusable allocation, `mmap`, guard pages, and allocator
return-to-OS behavior are later work.

Rust `std`, libc `malloc`, and C++ allocation should not each invent separate
heap growth mechanisms. They may have different allocator frontends, but they
should share the same hosted heap primitive.

## Time

K16 currently has timer0 game ticks and monotonic nanosecond helpers at lower
levels. Hosted ABI v0 should distinguish:

- monotonic time for durations and timeouts;
- wall-clock time, if unsupported;
- game ticks as KraftOS/K16-specific time.

Rust `Instant` and libc clock APIs can map to monotonic time when available.
`SystemTime` or wall-clock functions should return unsupported until there is a
real wall-clock source.

## Unsupported APIs

Unsupported hosted APIs must fail explicitly.

V0 should not implement these as no-op success:

- threads;
- condition variables and parking;
- sockets/networking;
- `fork`;
- POSIX signals;
- process groups and sessions;
- dynamic linking;
- filesystem permissions and ownership;
- full wall-clock time;
- C++ exceptions/unwind, until deliberately supported.

Some synchronization primitives may have valid single-thread implementations.
For example, a mutex can be trivial while K16 is single-threaded. That decision
must be explicit and documented. APIs that require blocking, waking, or
parallel execution should return unsupported or abort until the OS model
supports them.

## Rust Partial Std

The first Rust hosted proof should be intentionally small:

```rust
fn main() {
    println!("hello");
}
```

The proof should validate:

- building `std` for the K16 target in the prepared toolchain;
- target-specific Rust startup;
- panic-abort integration;
- stdout writes;
- process exit status.

The first proof should not include `std::thread`, networking, dynamic linking,
or broad filesystem coverage. After stdout and exit work, the next useful Rust
`std` slices are `std::env::args`, `std::fs::File`, `std::fs::metadata`,
`std::fs::read_dir`, and `std::time::Instant`.

## Libc And Libc++

Libc should use the same hosted ABI primitives:

- `crt0`;
- `exit`;
- `read`, `write`, `open`, `close`, `lseek`, `stat`;
- `brk`/`sbrk`;
- `errno`;
- basic `getcwd`/`chdir`;
- basic time calls when available.

Libc++ should sit above libc and libc++abi. The first C++ stage should avoid
exceptions and unwinding. A practical initial policy is `-fno-exceptions` and
`panic=abort`-style termination for fatal runtime boundaries. Full C++
exceptions, personality functions, unwinding tables, and TLS are later
milestones.

## Relationship To Existing Docs

Existing Rust feasibility docs correctly described hosted `std` as not
near-term when K16 did not yet have process, filesystem, heap, and userland
contracts. That assessment should now be refined rather than deleted.

K16 still should not pretend to be a full hosted OS, but the project now has
enough userland surface to make a partial hosted ABI a credible milestone.
The old warning remains valid for no-op or hidden-host behavior. The new
direction is a strict partial hosted ABI with explicit unsupported failures.

## First Follow-Up Slice

> Follow-up: [#333](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/333)

The first implementation issue should not attempt a full sysroot. It should
prove the narrowest hosted path:

```text
K16 partial std stage 0:
ordinary Rust `fn main()` -> println/stdout -> EXIT(0)
```

That slice should touch:

- K16 target/sysroot build wiring;
- Rust startup or `lang_start` integration;
- panic-abort path;
- stdout write path;
- one smoke program packaged as K16E and run under the existing VM/runtime
  test path.

Only after this proof should the project broaden into `std::fs`, libc `stdio`,
or C++ runtime support.

## Non-Goals

- No full POSIX compatibility in v0.
- No Linux-like process model requirement.
- No thread support in the first hosted ABI slice.
- No sockets/networking in the first hosted ABI slice.
- No C++ exceptions/unwind in the first C++ slice.
- No hidden host filesystem, clock, or process fallback.
- No no-op success for unsupported APIs.

## Verification Strategy

Design-level verification:

- check this spec against current `kraft-std`, process, fs, heap, and Rust
  feasibility docs;
- keep the roadmap issue current with the accepted layering decision;
- run `git diff --check`.

Implementation-level verification for later issues:

- sysroot build tests for the K16 target;
- minimal hosted Rust stdout/exit smoke;
- libc `write`/`exit` smoke;
- filesystem and argv smoke only after the stage-0 proof is stable;
- C++ smoke with exceptions disabled before any C++ exception support is
  attempted.
