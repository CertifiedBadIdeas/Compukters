# Low VM CKL OS Computer Design

## Goal

Move the experimental low VM computer model toward a hardware-like architecture where Rust provides a small virtual computer and CKL owns the operating-system layer.

The target model is:

```text
Rust low VM
  -> CPU core
  -> flat physical RAM
  -> MMIO devices
  -> boot entry

CKL kernel
  -> process table
  -> scheduler
  -> program loader
  -> syscall ABI
  -> shell/runtime services
```

This is intentionally different from the current production daemon model, where Rust owns processes, scheduling, hostcalls, filesystem routing, and display orchestration.

## Motivation

The project is exploring a lower-level VM direction:

- one shared machine RAM instead of per-process image memory;
- CPU contexts as execution state, not process objects;
- devices exposed through memory-mapped registers or explicit trap/syscall boundaries;
- CKL programs that can eventually behave like kernel, shell, drivers, and user programs;
- a reusable VM core for computers and future simpler devices.

If the computer is meant to feel like a small real machine, then processes should not be a hidden Rust runtime feature forever. A CKL OS gives the project one coherent mental model: Rust emulates the machine; CKL software defines what runs on it.

## Non-Goals

- Do not merge this experiment into `dev` until it has a working bootable slice.
- Do not replace the production Rust daemon in this design.
- Do not implement a full filesystem, shell, display driver, or userland in the first slice.
- Do not add virtual memory, MMU, paging, privilege rings, or memory protection in this phase.
- Do not require microcontroller support in the first slice.
- Do not introduce garbage collection or managed VM objects.
- Do not preserve old VM fallbacks inside this experimental path.
- Do not expose host pointers or unsafe host memory to CKL.

## Machine Boundary

Rust owns the machine:

```rust
struct ComputerMachine {
    bus: MachineBus,
    cpus: Vec<CpuContext>,
    boot_cpu: Option<CpuId>,
}
```

Rust is responsible for:

- loading the boot image into RAM or decoded code storage;
- exposing a deterministic CPU step/run API;
- validating memory accesses;
- routing MMIO reads and writes;
- reporting machine faults;
- enforcing quotas/slice budgets from outside the guest.

Rust is not responsible for:

- deciding what a CKL process is;
- maintaining a guest process table;
- switching guest tasks at the OS level;
- interpreting guest file descriptors;
- implementing shell command semantics;
- deciding userland ABI policy.

Those are CKL kernel responsibilities.

## CPU Contexts

A CPU context owns only execution state:

```rust
struct CpuContext {
    program: LowProgram,
    state: LowState,
    slice_budget: Duration,
}
```

It does not own RAM. When a CPU context runs, `ComputerMachine` passes the shared bus into the interpreter:

```rust
machine.run_cpu_until_signal(cpu_id)
```

The first CKL OS slice should use one boot CPU. Multiple CPU contexts can remain an experimental capability, but the CKL kernel MVP should not depend on SMP or parallel CPUs.

## Physical Memory Model

The computer has one flat physical RAM space addressed by `u32`.

Initial layout:

```text
0x0000_0000  boot/kernel image data
0x0001_0000  kernel globals and heap
0x0008_0000  user program area
0x0010_0000  guest stacks
0x1000_0000  MMIO window
```

Exact addresses can change, but the model should stay simple:

- no per-process Rust memory objects;
- no virtual addresses;
- no protection between kernel and user memory in the first slice;
- all guest isolation is cooperative and implemented by CKL convention.

Rust still checks every memory access against machine RAM or mapped MMIO regions. Guest bugs become VM faults, not host memory unsafety.

## Boot Flow

The first bootable slice is:

1. Rust creates `ComputerMachine`.
2. Rust loads one CKL kernel image.
3. Rust creates one boot CPU context.
4. The boot CPU starts at the image entry function.
5. CKL kernel initializes a small OS state block in RAM.
6. CKL kernel writes machine status through the existing control MMIO registers:
   - `RESET`
   - `BOOTING`
   - `READY`
   - `PANIC`
7. Rust test code observes the status and panic code through `ComputerMachine`.

This keeps the first milestone observable without requiring shell, files, or display.

## CKL Kernel MVP

The first CKL kernel should prove only these concepts:

- a kernel-owned OS state block in RAM;
- a fixed-size guest process table;
- one runnable "process" represented by saved guest registers or a program descriptor;
- a cooperative scheduler function;
- a panic path that writes a panic code to control MMIO;
- a ready path that writes `READY` to control MMIO.

The process table can be intentionally primitive:

```text
struct ProcessEntry {
    state: i32
    entry: u32
    stack_ptr: u32
    exit_code: i32
}
```

The initial kernel does not need dynamic process spawn. A static process entry is enough to prove ownership has moved into CKL.

## Syscall Strategy

The CKL OS should eventually expose syscalls to user programs. The first slice should define the ABI shape but avoid implementing broad services.

Recommended low-level syscall boundary:

```text
r0 = syscall_number
r1..rN = arguments
trap/syscall instruction or reserved MMIO write
r0 = result
```

The implementation choice can start with a simple low VM instruction later, but the design should keep the concept clear:

- user programs ask the CKL kernel for services;
- CKL kernel talks to Rust devices through MMIO or machine traps;
- Rust does not directly implement userland process semantics.

If a syscall instruction does not exist yet, the first kernel slice can call scheduler functions directly and postpone user/kernel trap mechanics.

## Device Strategy

Keep device support tiny at first:

- control MMIO for boot status and panic code;
- optional timer/counter MMIO if scheduling tests need time;
- no display, filesystem, terminal, or storage device in the first CKL OS slice.

Display and filesystem are high-value product features, but they should be added only after the boot/kernel/process proof works. Otherwise the branch will turn into device-framework work before the OS boundary is validated.

## Relationship To Production Runtime

This branch remains experimental.

Production `dev` keeps:

- Rust daemon scheduler;
- Rust process table;
- Rust hostcalls;
- existing display/filesystem/terminal integration;
- current CKL compatibility path.

The experimental branch explores whether a future architecture can move process semantics into CKL. It should not block shipping improvements on `dev`.

## First Implementation Slice

Implement only enough to prove a CKL-owned OS boundary:

1. Refactor low VM CPU execution so CPU state can run against machine-owned shared RAM without borrowing the bus for the CPU lifetime.
2. Add `ComputerMachine` APIs for owning boot CPU contexts.
3. Add a minimal CKL/low-image kernel fixture that writes `BOOTING`, initializes an OS state block, then writes `READY`.
4. Add a second fixture that writes `PANIC` and a panic code.
5. Add Rust tests that boot both fixtures and inspect machine control registers.

No shell. No dynamic loader. No filesystem. No display driver. No real user process scheduling yet.

## Success Criteria

- `ComputerMachine` owns RAM, bus, and CPU contexts.
- A boot CPU can run without exposing `LowImageCpu<'_>` outside the machine.
- A CKL kernel fixture can mark the machine ready through MMIO.
- A CKL kernel fixture can mark the machine panicked through MMIO.
- The design does not require Rust-owned guest process semantics.
- Existing low VM tests still pass in the experimental branch.

## Risks

- Without virtual memory, a bad user program can corrupt kernel state. This is acceptable for the first slice because the OS is cooperative.
- A CKL OS can easily become too large before any product value appears. Keep each slice bootable and testable.
- The current CKL compiler may not yet express enough low-level constructs. Kernel fixtures can start as low-image builders or generated fixtures until CKL catches up.
- MMIO can become a broad device framework too early. Keep only control registers until the OS boundary is proven.

## Deferred Work

- user/kernel trap instruction;
- dynamic process spawn;
- ELF-like or custom program loader;
- filesystem device;
- display framebuffer device;
- terminal/input device;
- virtual memory or memory protection;
- microcontroller-specific board model;
- Rust-to-CKL ABI constants generation.
