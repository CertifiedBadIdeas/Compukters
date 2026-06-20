# K16 Process Model V2

> Issue: [#319](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/319)

## Context

K16 userland currently has a bounded cooperative foreground process model. The
kernel launches translated `/bin/init.kx`, init synchronously launches
`/bin/shell.kx`, and the shell synchronously launches foreground utilities
through `RUN`. `RUN` saves the parent trap frame, marks the parent blocked on a
child, enters the child, and `EXIT` later restores the parent with the child
status in `r0`.

This model has been useful for proving the user/kernel boundary, translated
address spaces, process-owned file descriptors, `BRK`/`SBRK`, argv setup, and
fault recovery. Its limit is now visible: it is fixed around the current
foreground chain and does not define process identity, general child ownership,
wait queues, orphan handling, or background execution.

The v2 direction should keep K16 small enough for the mod computer use case.
It should not turn K16 into a full POSIX clone, but it should stop encoding
process lifecycle as "the currently running slot plus one blocked child".

## Current Model

Current behavior remains valid:

- `RUN(path, len, 0)` and `RUN(request, len, 1)` are synchronous compatibility
  syscalls.
- A successful `RUN` returns the child's non-negative exit status to the
  caller.
- A failed launch or runtime boundary returns an existing negative K16 error.
- `EXIT(status)` terminates the current process. If it has a blocked parent,
  the parent resumes. If init exits, the kernel halts the VM with that status.
- Regular file descriptors are process-owned. Stdio descriptors `0..=2` remain
  shared kernel conventions.
- Translated processes own address-space ids, backing pages, heap backing
  pages, and saved trap state. `EXIT` releases the exiting process resources.
- There is no preemption, fork, pipe, signal model, or background shell syntax.

The current kernel has a fixed process table and explicit lifecycle states:
empty, running, and blocked-on-child. That is enough for
init -> shell -> utility, but it is not a general process model.

## Approaches Considered

### A. Keep Extending Synchronous RUN

This would keep `RUN` as the only process creation API and grow the fixed
foreground depth or add ad hoc nested slots. It is easy to implement, but it
continues to make process identity implicit. It would also make later features
like background tasks, service processes, or shell job control harder because
there is no durable child handle to wait on.

### B. Add Minimal PID-Based Spawn/Wait

This adds explicit process identity and a parent-owned child lifecycle while
preserving the cooperative single-CPU execution style. `spawn` creates a child
and returns a PID. `wait` collects a child exit status. `RUN` becomes a
compatibility wrapper equivalent to `spawn` followed by `wait` for one child.
There is still no preemption, fork, signals, or POSIX process group model.

This is the recommended model. It gives K16 a real process contract without
forcing a full scheduler rewrite as the first implementation slice.

### C. Jump Directly To Scheduler And Background Jobs

This would introduce runnable queues, sleeping queues, background processes,
and possibly shell job control in one step. It is closer to a familiar OS, but
it is too much for the next K16 slice. The current userland still benefits from
better identity and wait semantics before it needs preemption or job control.

## Accepted Direction

K16 process model v2 should be a minimal PID-based cooperative process model.

The kernel should treat a process as a durable object with:

- a stable PID while the slot is alive;
- a parent PID, except for init;
- a lifecycle state;
- an exit status once terminated;
- a resource bundle containing address-space id, physical backing pages,
  heap backing pages, regular file descriptors, saved trap frame, argv area,
  and process memory bounds.

The first v2 implementation should keep one active user process at a time. It
does not need preemption or parallel runnable queues to justify PID semantics.
The value of v2 is that `RUN`, future `spawn`, and future `wait` all operate on
the same process table semantics instead of special-case parent/child slots.

## Process Identity

PID allocation should be kernel-owned and monotonic with bounded reuse.
PID `0` should be reserved as invalid. Init should have PID `1`. The kernel may
keep slot indexes internally, but userland should not observe slots.

A PID remains valid until its process is reaped or, for init, until the machine
halts. If the kernel later reuses a slot, the PID generation must prevent an
old PID from accidentally naming the new process. A simple `u32 next_pid`
counter is enough for the first version; wraparound can return `ERROR_NO_MEMORY`
or another existing launch failure until a later generation scheme is needed.

## Lifecycle States

The v2 process table should distinguish these states:

- `Empty`: reusable table entry with no process resources.
- `Runnable`: process can enter user mode when selected.
- `BlockedOnChild`: process is waiting for a specific child or any child.
- `BlockedOnIo`: process is waiting for a kernel I/O event such as stdin.
- `Exited`: process has an exit status and waits to be reaped by its parent.

The first implementation slice does not need a general scheduler. It may map
current behavior to `Runnable` and `BlockedOnChild` only, but the state names
should leave room for stdin blocking and future background tasks without
changing the public model.

## Spawn And Wait

The target syscalls are:

- `SPAWN(request, len) -> pid | negative_error`
- `WAIT(pid_or_zero, out_status_ptr) -> pid | negative_error`

`SPAWN` loads a dynamic K16E program from ROOT/K16FS, creates a process, copies
argv, and returns the child PID. In the first cooperative slice, the child may
run immediately after `SPAWN` if that is the simplest scheduler policy. The key
contract is that process identity exists before completion and `WAIT` collects
the status.

`WAIT(0, out)` waits for any direct child. `WAIT(pid, out)` waits for one
direct child. If the child has already exited, `WAIT` returns immediately,
writes the child status, releases the child's remaining reaped state, and
returns the reaped PID. If the process is not a child of the caller, `WAIT`
returns `ERROR_INVALID`.

The exact syscall numbers are intentionally not assigned in this spec. That
should happen in the first implementation issue when the ABI table is updated.

## RUN Compatibility

Current `RUN` must stay synchronous and source-compatible for existing
`kraft-std::process::run`, `run_with_args`, init, shell, and bundled utilities.

Under v2, `RUN` should be defined as a compatibility syscall:

1. Create a child using the same loader and argv semantics as `SPAWN`.
2. Block the caller on that child.
3. Run the child cooperatively.
4. Return the non-negative child exit status or a negative launch/runtime error.
5. Reap the child before returning to the caller.

This keeps shell behavior stable while allowing new userland code to use
`SPAWN` and `WAIT` later.

## Init And Orphans

Init is the root process and should have PID `1`. In v2, init has two roles:

- launch the shell or future system services;
- adopt orphaned child processes when a parent exits before reaping them.

The first implementation may forbid parent exit while it has live children if
that is simpler, but the target model should be adoption by init. Adopted
exited children remain waitable by init. If init exits, the kernel halts the
VM and releases process-owned resources as part of machine shutdown.

## Resource Ownership

Each process owns:

- regular file descriptors returned by `OPEN`;
- its translated address-space id;
- loaded image backing pages;
- heap backing pages committed by `BRK`/`SBRK`;
- saved trap frame and process memory bounds;
- argv bytes copied at launch.

`EXIT` closes regular fds, records the exit status, and moves the process to
`Exited` if a parent can reap it. The final backing pages and address space can
be released at `EXIT` if no future inspection needs them. The process table may
keep only PID, parent PID, status, and minimal wait metadata until reap.

Stdio remains a shared kernel convention in the near term. There is no fd
inheritance across process creation until an explicit inheritance API exists.

## Error Handling

The v2 model should reuse existing negative K16 errors where possible:

- invalid PID, invalid wait target, malformed request: `ERROR_INVALID`;
- missing executable: `ERROR_NO_ENTRY`;
- invalid executable or user pointer fault: `ERROR_FAULT` or existing loader
  error mapping;
- no free process entry, no PID, no memory: `ERROR_NO_MEMORY`;
- spawning while the implementation cannot support another live child:
  `ERROR_BUSY`.

New status names should be added only when existing errors lose important
debugging information.

## First Implementation Slice

The first code issue should not implement the full target at once. It should:

1. Refactor the kernel process table to store explicit PID and parent PID
   metadata while preserving current `RUN` behavior.
2. Add host-side process-table tests for PID allocation, parent linkage,
   exit-status recording, and reaping metadata.
3. Keep `RUN` as the only guest-visible process creation syscall.
4. Update ABI docs to describe the internal v2-ready process table while
   stating that `SPAWN`/`WAIT` are not exposed yet.

The second slice can add `WAIT` over already-exited direct children if the
process table is ready. The third slice can add `SPAWN` as a guest-visible
syscall. Background shell syntax should remain later work.

## Non-Goals

- No `fork`.
- No POSIX signals.
- No process groups, sessions, or terminal job control.
- No preemptive scheduler in the v2 base model.
- No multicore process scheduling.
- No fd inheritance until a separate issue defines it.
- No guarantee that K16 PID semantics match Linux or OpenComputers exactly.

## Verification Strategy

Process model work should be proven in layers:

- pure process-table unit tests for lifecycle transitions;
- kernel syscall tests for launch, exit, wait, and error mapping;
- runtime smoke tests that boot init/shell and run utilities unchanged through
  the `RUN` compatibility path;
- later shell smoke tests for `spawn`/`wait` commands only after those commands
  exist.

The compatibility invariant is simple: current shell workflows must not change
when the internal process table becomes v2-ready.
