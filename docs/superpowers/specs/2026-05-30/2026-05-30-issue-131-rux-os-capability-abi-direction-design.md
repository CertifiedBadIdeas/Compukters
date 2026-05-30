# Rux OS Capability ABI Direction Design

> Issue: [#131](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/131)

## Context

Rux is no longer only a single-program firmware experiment. The current
architecture has a Rux16 CPU substrate, RUXE executables, storage-backed boot,
kernel/init handoff blocks, and the first trap/syscall path. LLVM readiness is
also being developed as an external toolchain path that lowers code into
normal Rux16 artifacts without making the VM depend on LLVM.

That creates a long-term OS question: should Rux copy POSIX/Unix internally, or
should it expose POSIX-like behavior only as a compatibility layer over a more
explicit kernel ABI?

This spec records the direction. It is a design anchor, not an implementation
slice.

## Decision

Rux OS should not use POSIX as its internal kernel ABI.

POSIX/Unix compatibility is allowed as a user-facing compatibility facade:
`open`, `read`, `write`, `close`, `spawn`, path strings, stdio, and a libc-like
surface may exist later so conventional software can be ported or emulated.
Those APIs should lower into Rux-native services instead of defining the
kernel boundary.

The internal Rux OS ABI should be capability-first:

- resources are explicit handles with rights;
- handles have typed resource kinds;
- authority flows through handles, not ambient global names;
- filesystem paths are namespace service policy, not universal authority;
- process creation is explicit `spawn` with a declared capability set, not
  implicit `fork` state cloning;
- operations are typed service requests, not unstructured `ioctl`-style bags;
- deterministic events, snapshots, and replayable machine state remain first
  class design constraints.

## Layering Rule

The layering must remain:

```text
Minecraft host / loader integration
  -> machine model and devices
      -> Rux16 VM core
          -> instruction execution
          -> registers, traps, memory accesses
          -> device-visible machine ABI
      -> Rux OS kernel and services
          -> capability handles
          -> process/resource policy
          -> service messages
      -> compatibility libraries
          -> POSIX-like APIs when useful
      -> external toolchains
          -> LLVM backend and object pipeline
```

The VM does not know about POSIX, libc, LLVM IR, LLVM object internals,
process tables, users, current working directories, file descriptors, or shell
semantics. It only executes Rux16 code against documented machine state.

The OS may use trap/syscall mechanics to route user requests into kernel-owned
services, but those services are Rux OS behavior, not VM behavior.

LLVM remains an external producer of Rux16 objects and RUXE programs. LLVM
support must not add special VM hooks, POSIX assumptions, or alternate runtime
paths for LLVM-generated code.

## Resource Model Sketch

The first stable OS ABI should model resources with small primitive fields that
are easy to pass through Rux16 registers and memory blocks:

```text
HandleId      u32
Rights        u32 bitset
ResourceKind  u32 enum
Status        u32 enum
MessageKind   u32 enum
```

Initial resource kinds should stay deliberately small:

```text
0  invalid
1  process
2  memory object
3  byte stream
4  filesystem namespace
5  file object
6  directory object
7  storage volume
8  terminal session
9  clock or timer
```

The exact numeric table can be specified later. The important design rule is
that a handle identifies both authority and resource type. User code should not
infer authority from a global path, a process id, or a magic address.

Rights should describe what the holder may do:

```text
read
write
seek
stat
map
spawn
send
receive
control
duplicate
close
```

Rights are not POSIX mode bits. They are direct capabilities attached to live
handles and transferred deliberately.

## Syscall Shape

The current trap/syscall work can evolve toward a typed request boundary:

```text
r0 = service or syscall class
r1 = operation
r2 = pointer to request block
r3 = request block byte length
trap
r0 = status
r1 = result value or pointer to response block
```

Small fixed operations may still use register-only arguments, but service
requests that carry handles, buffers, or structured metadata should prefer
versioned memory blocks. This keeps the register ABI small while allowing the
OS ABI to evolve without adding a new trap instruction for every operation.

Unsupported service classes, operations, versions, rights, or resource kinds
must fail explicitly. There should be no compatibility fallback path that
silently reinterprets a request as a POSIX operation, raw MMIO access, or host
filesystem call.

## Spawn Instead Of Fork

Rux should prefer explicit spawn semantics:

```text
spawn(program, argv_block, inherited_handles, flags) -> process_handle
```

The caller chooses which capabilities the child receives. A new process starts
from a RUXE program image and an explicit startup block. It does not inherit a
copy of the entire address space, open descriptor table, signal state, current
directory, environment, and process-global ambient authority by default.

This fits the current RUXE and kernel/init direction better than POSIX `fork`.
It also keeps future snapshot and deterministic replay behavior tractable.

## Namespaces And Filesystems

Rux may still expose path-based APIs through a namespace service:

```text
namespace_handle.resolve(path, rights) -> handle
file_handle.read(buffer) -> bytes_read
file_handle.write(buffer) -> bytes_written
```

The path is input to a specific namespace handle. It is not ambient authority.
Different processes can receive different namespace handles, even if their
path strings look the same.

This keeps Unix-like ergonomics available without forcing the kernel ABI to
treat "everything is a file" as the core resource model. Devices, timers,
storage volumes, process handles, and terminal sessions can have typed
operations when a byte stream abstraction would be lossy.

## Compatibility Facade

A future POSIX-like library can map familiar APIs onto Rux capabilities:

```text
open(path, flags)      -> namespace resolve + rights request
read(fd, buf, len)     -> byte-stream read
write(fd, buf, len)    -> byte-stream write
close(fd)             -> close handle
exec(path, argv)       -> resolve executable + spawn
pipe()                -> create byte-stream pair
wait(pid)             -> wait on process handle
```

That layer may intentionally be incomplete. It should be a porting aid, not
the source of truth for the kernel ABI.

## Out Of Scope

- Implementing this ABI in code.
- Defining final syscall numbers or binary request layouts.
- Adding privilege rings, virtual memory, users, groups, POSIX signals, or a
  full process scheduler.
- Porting libc, compiler-rt, Rust `std`, or Unix userland.
- Adding POSIX or LLVM dependencies to the VM.
- Replacing current boot, RUXE, or trap/syscall slices.

## Consequences

- Future syscall specs should talk in terms of services, handles, rights, and
  typed request blocks before introducing POSIX names.
- RUXE `program` startup should evolve toward an explicit startup block with
  inherited handles rather than implicit global stdio or working-directory
  state.
- Filesystem work should define namespace and file-object capabilities, not
  only path-string helpers.
- Terminal and display work should decide whether a resource is a byte stream,
  a typed device service, or both.
- LLVM and language runtime work should target a freestanding Rux ABI first;
  hosted POSIX behavior can be layered later.
- The VM must continue to reject unsupported machine behavior explicitly
  rather than helping OS compatibility through hidden runtime paths.

## Follow-Up Slices

1. Define the first capability handle table ABI for kernel-owned resources.
2. Define a versioned syscall request-block layout.
3. Replace the fixed first trap syscall proof with a minimal typed service
   dispatcher.
4. Define process startup blocks for RUXE `program` images, including inherited
   handles.
5. Define namespace and file-object capabilities before adding broad
   `std::fs` APIs.
6. Decide the POSIX compatibility surface only after the native Rux service ABI
   is stable enough to host it.

## Verification

This is documentation-only. Verification is:

- `git diff --check`
- review that the spec preserves VM, OS, POSIX, and LLVM boundaries.
