# K16 Virtual Memory v1

> Issue: [#263](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/263)

## Status

Status: planned design.

This document defines the first intended K16 virtual-memory and process
address-space contract. It is not implemented by the current VM, kernel,
bootloader, or toolchain. Existing K16 systems remain physical-memory-only
until a later CPU/MMU ABI slice explicitly implements and enables this model.

The design is intentionally smaller than a desktop or server OS MMU. K16 is a
Minecraft mod computer, closer in spirit to OpenComputers than to commodity
hardware. The goal is to remove fragile physical userland layout constraints
and make process isolation understandable, not to implement fork, demand
paging, copy-on-write, shared libraries, or kernel virtual memory.

## Relationship To Current ABIs

`k16-machine-profile-v2.md` continues to define one 32-bit guest physical
address space. Its `page_size` field is still a boot-layout and MMIO alignment
granularity, not an MMU page.

`k16-cpu-v1.md` remains physical-memory-only. It already states that privilege
levels, virtual memory, and page-table translation are later ABI slices. Its
4-bit CSR namespace is currently full, so MMU controls must be introduced by a
future CPU ABI revision or an explicit MMU control device. They must not be
silently retrofitted into unused v1 behavior.

`k16e-v1.md` dynamic user programs remain base-relative executable images. VM
address spaces do not require a new executable container: the kernel may load a
dynamic `program` image into physical RAM, map it at a selected virtual base,
and enter the image at `virtual_base + entry_offset`.

## Goals

- Keep BIOS, bootloader, and early kernel execution physical and simple.
- Give each user process a stable virtual address space independent from the
  physical arena chosen by the kernel.
- Let the kernel validate user pointers by translation and page permissions
  instead of by hard-coded physical ranges.
- Keep the first implementation small enough for the current cooperative
  process model.
- Preserve the existing K16E dynamic-program direction.

## Non-Goals

- Full POSIX process semantics.
- `fork`, copy-on-write, `mmap`, demand paging, swap, shared libraries, TLS, or
  dynamic linking.
- A kernel virtual address map in the first implementation.
- User-accessible MMIO mappings.
- Multiple CPU cores, TLB shootdown, ASIDs, or SMP memory ordering.
- Retiring physical-mode firmware, boot, or kernel execution.

## Address Spaces

K16 has two execution address modes:

```text
physical mode       current behavior; instruction fetches, loads, and stores
                    use guest physical addresses
user-translated     instruction fetches, loads, and stores use the current
                    process page table
```

The VM starts in physical mode. BIOS, bootloader, and kernel entry run in
physical mode. The kernel may enter user-translated mode only after it has
constructed a page table for the target process.

Trap and interrupt entry switches back to physical mode before executing the
kernel trap vector. `iret` restores the interrupted address mode together with
the interrupted PC and stack pointer. This keeps the first kernel simple: the
kernel code and data stay physical, while user code runs translated.

In user-translated mode, guest MMIO is not directly mappable in v1. User
programs reach devices through syscalls and kernel services.

## Virtual Layout

The first user layout reserves a null page and leaves the high half unmapped
for future kernel or shared mappings:

```text
0x0000_0000..0x0000_0fff  unmapped null guard
0x0001_0000..0x7fff_ffff  user image, heap, argv, and stack mappings
0x8000_0000..0xffff_ffff  reserved, unmapped in v1 user processes
```

The kernel chooses the exact user image virtual base. The recommended first
base is `0x0001_0000`, page-aligned, so low null and near-null pointer bugs
fault deterministically. The user stack grows downward from a kernel-selected
virtual stack top below `0x8000_0000`.

Fixed-image `program` artifacts remain useful for standalone physical-mode
tooling and compatibility tests. Normal OS-launched user processes should use
dynamic `program` artifacts so the kernel can choose both the physical load
arena and the virtual mapping.

## Page Size

Virtual memory v1 uses a fixed MMU page size:

```text
K16_VM_PAGE_SIZE = 4096 bytes
```

This is independent from profile v2 `BootInfo.page_size`. The current computer
profile uses a 148 KiB RAM size, which is exactly 37 VM pages. A 4 KiB MMU page
keeps page tables small enough for the mod computer while avoiding a large
number of tiny 256-byte PTEs.

## Page Tables

Virtual memory v1 uses a two-level 32-bit page table with 4 KiB pages.

```text
virtual address bits:
31..22  level-1 index
21..12  level-2 index
11..0   page offset
```

Each page-table page is 4096 bytes and contains 1024 little-endian `u32`
entries. The current page-table root is a guest physical address aligned to
4096 bytes.

PDE and PTE format:

```text
bit  0      valid
bit  1      user
bit  2      writable
bit  3      executable
bits 4..11  reserved, must be zero
bits 12..31 physical page base bits 12..31
```

A level-1 entry points to a level-2 page table. A level-2 entry points to a
physical data/code page. Both levels must have `valid = 1`. Reserved bits must
be zero. Physical page bases must be 4096-byte aligned and must point inside
guest RAM. v1 page tables do not map MMIO ranges.

The first implementation does not need ASIDs or a guest-visible TLB. If the VM
adds an internal TLB later, changing the active page-table root or any valid
PTE must make subsequent translated accesses observe the new mapping before
returning to user code.

## Permissions

Translated accesses check permissions after translation:

```text
instruction fetch  requires valid, user, executable
load               requires valid, user
store              requires valid, user, writable
```

The first design has no supervisor-translated mode. Kernel code runs in
physical mode and must not directly dereference user virtual pointers. Kernel
syscall handlers copy user buffers through explicit translation helpers that
check the interrupted process page table and requested access type.

Executable pages are not implicitly readable by instruction fetch. Ordinary
loads from code pages are allowed only if the PTE also satisfies the load
rules. v1 does not define a separate readable bit; a valid user page is
readable unless the access is an instruction fetch or store that needs stronger
permission.

## Faults

Failed translation or permission checks raise a synchronous page fault. The
faulting instruction does not retire.

Planned trap fields for the MMU ABI slice:

```text
trap_cause  page fault cause value assigned by the CPU ABI revision
trap_pc     faulting instruction PC
trap_value  faulting virtual address
trap_arg0   access kind: 1 = fetch, 2 = load, 3 = store
trap_arg1   fault reason: 1 = not present, 2 = permission, 3 = malformed table
```

If no trap vector is installed, the VM reports a hard CPU trap to the host, as
with other synchronous CPU exceptions. If a trap vector is installed, the CPU
enters it in physical mode.

Malformed page tables are guest faults, not host panics. Examples include
reserved bits set, unaligned table/page base, physical page outside RAM, or
attempted MMIO mapping.

## Kernel And Syscalls

The kernel owns all page tables. User processes cannot write page tables
directly unless the kernel deliberately maps a page-table page writable into a
user address space, which v1 forbids.

For syscall arguments that are guest pointers:

1. The CPU enters the trap vector in physical mode.
2. The kernel reads the interrupted process page-table root from its process
   table or saved trap metadata.
3. The kernel translates and copies user buffers using access-specific helpers.
4. Invalid user pointers return existing negative K16 `ERROR_FAULT` where the
   current syscall ABI expects recoverable pointer errors.
5. CPU instruction-fetch/load/store faults in user code enter the trap vector
   as page faults and may terminate the current process in the first kernel
   implementation.

This replaces the current physical `memory_start..memory_end` pointer check for
VM-enabled user processes. Physical process ranges may remain as an internal
allocator constraint for the kernel-selected backing pages.

## Loading Processes

Physical boot flow remains unchanged:

```text
host -> BIOS flash -> bootloader -> kernel in physical mode
```

The first VM-enabled user launch should:

1. Load a dynamic K16E `program` image into kernel-selected physical pages.
2. Allocate zero-filled physical pages for `.bss`, heap, argv, and stack.
3. Create a page table mapping those pages into the process virtual layout.
4. Enter user-translated mode at `user_image_base + entry_offset`.
5. Set user `sp` to the selected virtual stack top.

The dynamic image relocation base becomes the selected virtual image base, not
the physical load base. This lets user pointers remain stable while physical
backing pages are chosen by the kernel.

## Implementation Sequence

The intended follow-up slices are:

1. Add a VM-internal address translation module and tests while leaving
   physical mode as the default and only active runtime mode.
2. Add explicit CPU/MMU control state in a versioned ABI slice, including page
   fault reporting and mode switching on trap/`iret`.
3. Add kernel-owned page-table construction for one child process while init
   can remain physical.
4. Convert syscall user-buffer validation from physical range checks to
   translation-based copy helpers for VM-enabled processes.
5. Move init and shell into VM-enabled user processes after the child path is
   stable.

Each slice should keep existing physical boot and storage behavior working.

## Compatibility Notes

- Existing physical-mode K16E bootloader and kernel artifacts remain valid.
- Existing dynamic user programs remain the preferred executable format for OS
  launches.
- Existing syscall numbers do not need to change merely because user pointers
  become virtual; the kernel changes how it validates and copies them.
- Snapshot and suspend/resume formats will need an explicit extension before
  VM-enabled process state can be persisted.
