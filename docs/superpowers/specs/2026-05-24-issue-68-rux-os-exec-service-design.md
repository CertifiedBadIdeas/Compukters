# Rux OS Exec Service Design

> Issue: [#68](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/68)

## Status

Draft for review.

## Context

Rux storage is moving toward a real machine model:

- `storage0` is a guest-visible block device;
- the BIOS discovers storage through `BootInfo` and `HardwareTable`;
- #62 covers BIOS-first boot from a persistent system volume.

After #62, the first program loaded from disk can become a small OS. That OS
will eventually need to launch additional programs from files such as
`/bin/shell.ruxi`, `/bin/ls.ruxi`, or `/apps/editor.ruxi`.

The current VM cannot treat arbitrary bytes in guest RAM as directly executable
machine code. `RUXI` bytes are decoded by the host-side VM into an internal
image before execution. Because of that, a future OS needs a temporary execution
primitive: the OS owns filesystem and process policy, while the VM helps decode
and spawn executable images from RAM buffers.

This must not become host-side path execution. The host must not know that a
program came from `/bin/foo`; it should only receive a RAM buffer containing
candidate executable bytes.

## Goals

- Define the boundary between OS-owned filesystem policy and VM-owned image
  decode/spawn mechanics.
- Provide a first exec model that can launch filesystem-backed `RUXI` programs
  without making the host understand filesystem paths.
- Preserve a migration path to a future guest-side loader.
- Keep BIOS handoff and normal post-boot program execution separate.

## Non-Goals

- No filesystem format, partition table, shell, users, permissions, packages, or
  dynamic linker in this issue.
- No host API that accepts paths such as `/bin/foo`.
- No change to frozen `RUXI` v1 instruction encoding.
- No requirement that the first implementation support fully isolated address
  spaces.

## Terminology

- **OS exec API**: the OS-facing operation such as `exec(path, argv)` or
  `spawn(path, argv)`. It resolves paths and reads files through the OS
  filesystem.
- **Exec service**: the low-level VM service that receives executable bytes from
  guest RAM and attempts to create a process.
- **Guest-side loader**: a future loader where the OS can parse and map an
  executable format itself without host-side RUXI decode help.

## Architecture

The intended layering is:

```text
shell / init / user program
  -> OS exec(path, argv)
    -> OS filesystem reads file from storage0
      -> OS copies executable bytes into RAM
        -> exec service receives buffer address + length
          -> VM decodes RUXI and creates a process/image instance
```

The exec service must not accept filesystem paths. It should accept only guest
RAM addresses, byte lengths, argument/environment buffers, and handle ids.

The host/VM side owns:

- reading bytes from the supplied RAM range;
- validating and decoding the `RUXI` image;
- creating a process/image instance;
- returning a PID or structured error.

The OS side owns:

- path lookup;
- filesystem traversal;
- executable file selection;
- permission policy, once permissions exist;
- argv/env construction;
- standard handle assignment;
- wait/exit policy.

This makes the first implementation a backend for an OS syscall, not the OS
policy model itself.

## Proposed Low-Level Contract

The exact transport can be MMIO, syscall, or another VM call surface. The
contract should be equivalent to:

```text
exec_spawn_ruxi(
  image_addr: u32,
  image_len: u32,
  argv_addr: u32,
  argv_len: u32,
  env_addr: u32,
  env_len: u32,
  stdin_handle: u32,
  stdout_handle: u32,
  stderr_handle: u32,
) -> ExecResult
```

`image_addr..image_addr + image_len` is a guest RAM range containing the
complete candidate `RUXI` byte stream. The service must reject buffers outside
guest RAM or ranges that overflow the address space.

`argv` and `env` should be serialized guest-owned data. The first slice may keep
this minimal, for example null-separated UTF-8 strings or a compact length
prefixed table. The encoding must be documented before implementation.

Handle ids are OS/kernel-level references. The first slice may map them to a
minimal standard stream model, but the service should not bake in Minecraft UI
or filesystem behavior.

## OS-Level API

The future OS API should stay path-oriented:

```text
spawn(path: string, argv: list<string>) -> pid | error
exec(path: string, argv: list<string>) -> never | error
wait(pid) -> exit_status
exit(code)
```

Implementation sketch:

```text
spawn("/bin/ls.ruxi", ["ls", "/"])
  -> open("/bin/ls.ruxi")
  -> read file bytes into RAM buffer
  -> call exec_spawn_ruxi(buffer, len, argv, env, std handles)
  -> return pid
```

This keeps `/bin/*` semantics inside the OS. If the filesystem changes later,
the exec service does not change.

## Relationship To Boot Handoff

Boot handoff and normal exec are separate.

Boot handoff (#62):

```text
BIOS -> reads boot metadata -> requests first image handoff
```

Post-boot exec (#68):

```text
OS -> reads a file from its filesystem -> asks VM to spawn bytes from RAM
```

BIOS should not remain responsible for launching `/bin/*` programs. Once the
first OS image is running, BIOS is out of the normal program-launch path.

## Migration Path To Guest-Side Loading

This design should not block a future model where the OS loads executables
itself.

The compatibility point is the OS-level API:

```text
spawn(path, argv)
```

The implementation can evolve:

```text
Phase 1:
  spawn(path) -> read file -> exec service decodes RUXI -> VM process

Phase 2:
  spawn(path) -> guest loader parses executable -> maps memory -> starts process
```

As long as the exec service stays byte-buffer based and path-free, replacing it
later does not require changing filesystem semantics or shell APIs.

The first service can remain available as a compatibility backend for `RUXI`
programs even after a richer guest-loadable executable format exists.

## Error Model

The exec service should return structured errors, not panic the machine:

- `bad_buffer`: image, argv, or env buffer is outside guest RAM or overflows.
- `empty_image`: `image_len == 0`.
- `image_too_large`: image exceeds configured executable size limits.
- `invalid_image`: bytes are not valid `RUXI`.
- `unsupported_format`: image is valid bytes but not a supported executable
  format for this machine.
- `out_of_memory`: process/image allocation failed.
- `too_many_processes`: process table limit reached.
- `bad_handle`: one of stdin/stdout/stderr handles is invalid.

The OS should translate these into user-visible shell errors or process spawn
errors. The VM should not inspect or print the source path.

## Testing Strategy

Initial native coverage should avoid requiring a real filesystem:

- spawning a valid in-memory `RUXI` byte buffer returns a PID;
- invalid magic returns `invalid_image`;
- out-of-RAM buffer returns `bad_buffer`;
- oversized image returns `image_too_large`;
- process table exhaustion returns `too_many_processes`;
- spawned process can run to completion and report an exit status.

Later integration coverage can add:

- a tiny OS fixture reads a `RUXI` file from `storage0`;
- the OS launches it through the exec service;
- stdout/display proves the child program ran;
- the same OS-level `spawn(path, argv)` API remains stable if the backend changes.

## Open Follow-Ups

- Define the first filesystem format for `storage0`.
- Define the first process table and PID ownership model for the Rux OS path.
- Define argv/env serialization.
- Decide whether the exec transport is MMIO, syscall-like instruction surface, or
  another machine-profile-specific VM call.
