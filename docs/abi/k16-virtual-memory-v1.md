# K16 Virtual Memory v1

> Issues:
> [#263](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/263),
> [#273](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/273)

## Status

Status: partially implemented design.

This document defines the first intended K16 virtual-memory and process
address-space contract. The current VM implements the host MMU map, CPU
address/privilege modes, privileged `mmu0` map controls, and trap/`iret` mode
switching. The guest kernel also uses `mmu0` copy helpers for translated
syscall buffers, launches `/bin/init.kx` and `/bin/shell.kx` in host-managed
translated address spaces, and supports shell-started translated utility
children for the current fixed foreground depth. BIOS, bootloader, and kernel
still run in physical mode; a more general process lifecycle is intentionally
left for later process-model work.

The design is intentionally smaller than a desktop or server OS MMU, but it is
still a real host-enforced address translation boundary. K16 is a Minecraft mod
computer, closer in spirit to OpenComputers than to commodity hardware. The
goal is to remove fragile physical userland layout constraints and make
process isolation understandable, not to implement fork, demand paging,
copy-on-write, shared libraries, or kernel virtual memory.

## Relationship To Current ABIs

`k16-machine-profile-v2.md` continues to define one 32-bit guest physical
address space. Its `page_size` field is still a boot-layout and MMIO alignment
granularity, not an MMU page.

`k16-cpu-v1.md` defines the CPU-visible physical and user-translated execution
modes, trap entry back to physical/kernel mode, and `iret` restoration of the
saved mode. The host-managed MMU controls are exposed through the `mmu0`
computer-profile device rather than guest-visible page tables or new CPU
instruction encodings.

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
- Keep the MMU implementation in the host VM/runtime rather than requiring a
  guest-visible hardware page walker.
- Keep the first implementation small enough for the current cooperative
  process model.
- Preserve the existing K16E dynamic-program direction.

## Non-Goals

- Full POSIX process semantics.
- `fork`, copy-on-write, `mmap`, demand paging, swap, shared libraries, TLS, or
  dynamic linking.
- A kernel virtual address map in the first implementation.
- Guest RAM page-table walking in the first implementation.
- User-accessible MMIO mappings.
- Multiple CPU cores, TLB shootdown, ASIDs, or SMP memory ordering.
- Retiring physical-mode firmware, boot, or kernel execution.

## Address Spaces

K16 has two execution address modes:

```text
physical mode       current behavior; instruction fetches, loads, and stores
                    use guest physical addresses
user-translated     instruction fetches, loads, and stores are translated by
                    the host MMU through the current address-space map
```

The VM starts in physical mode. BIOS, bootloader, and kernel entry run in
physical mode. The kernel may enter user-translated mode only after it has
constructed an address-space map for the target process.

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
profile uses a 192 KiB RAM size, which is exactly 48 VM pages. A 4 KiB page
keeps the first address-space maps compact while still giving the kernel a
useful protection boundary for user image, heap, argv, and stack placement.

## Host MMU Map

Virtual memory v1 uses host-managed address-space maps, not guest RAM
page-table walking. A map is a list of non-overlapping page extents owned by
the VM/runtime and configured by the guest kernel through a future CPU/MMU ABI
slice.

This matches the host-implemented nature of the K16 MMU. A hardware-style
guest page-table walker is useful for real hardware, but it adds little value
to a small host-driven mod computer. Extent mappings keep the visible ABI small
while still providing real host-enforced translation, permissions, and page
faults.

Each mapping entry has these semantic fields:

```text
virtual_page     virtual start address / 4096
physical_page    physical RAM start address / 4096
page_count       number of contiguous pages in this extent
flags            user, writable, executable
```

The host MMU rejects mappings that overlap an existing virtual page range, use
zero `page_count`, point outside guest RAM, or point at MMIO. Physical bases
and virtual bases must be 4096-byte aligned. v1 does not support aliasing the
same virtual page to multiple physical pages.

User-accessible mappings are W^X: a mapping with both `writable` and
`executable` set is invalid. Loaders must map user code as executable and
read-only, and map user stack/heap/writable data as writable and non-executable.
Supervisor-only mappings are not constrained by this user W^X rule in v1.

The first implementation does not need ASIDs or a guest-visible TLB. If the VM
adds an internal TLB later, changing the active address-space map or any
mapping entry must make subsequent translated accesses observe the new mapping
before returning to user code.

## Minimum Host MMU Operations

The initial implemented CPU/MMU ABI slice exposes these operations to the guest
kernel through the `mmu0` MMIO control device:

```text
create_address_space() -> address_space_id
map_pages(address_space_id, virtual_page, physical_page, page_count, flags)
protect_pages(address_space_id, virtual_page, page_count, flags)
activate_user_address_space(address_space_id, entry_pc, user_stack_pointer, kernel_stack_pointer)
copy_from_user(address_space_id, user_virtual_addr, kernel_physical_addr, byte_count)
copy_to_user(address_space_id, user_virtual_addr, kernel_physical_addr, byte_count)
set_trap_return_physical()
set_trap_return_address_space(address_space_id, kernel_stack_pointer)
destroy_address_space(address_space_id)
```

`activate_user_address_space` switches the current K16 CPU to translated user
execution with a user stack pointer and a physical kernel trap stack pointer.
The command device itself does not own address spaces; it records guest
commands, and `ComputerMachine` applies them to its host-managed MMU registry
before guest execution continues. User translation is host-enforced, and the
kernel configures mappings instead of publishing raw page-table memory for the
host to walk.

`destroy_address_space` removes a host-managed address space and all mappings
owned by that address space. The current kernel uses it as part of translated
process `EXIT` cleanup before returning to the blocked parent frame. The first
slice still has no partial unmap syscall; short-lived process address spaces
are reclaimed as a whole. The copy commands let physical/kernel syscall
handlers move bytes across the user/kernel boundary without directly
dereferencing user virtual pointers.

`set_trap_return_physical` lets a physical/kernel trap handler override the
saved `iret` address mode after servicing a translated child. It remains a
compatibility command for transitional physical-parent paths, but the current
bundled init/shell/utility foreground chain uses translated parent resumes.

`set_trap_return_address_space` lets a physical/kernel trap handler override
the saved `iret` address mode to a translated parent address space and restore
that parent context's physical kernel trap stack pointer. The current
production process path uses it when a translated utility exits and the blocked
parent is the translated shell.

## Permissions

Translated accesses check permissions after translation:

```text
instruction fetch  requires mapped, user, executable
load               requires mapped, user
store              requires mapped, user, writable
```

The first design has no supervisor-translated mode. Kernel code runs in
physical mode and must not directly dereference user virtual pointers. Kernel
syscall handlers copy user buffers through explicit translation helpers that
check the interrupted process address-space map and requested access type.

Executable pages are not special for ordinary loads. v1 does not define a
separate readable bit; a mapped user page is readable unless the access is an
instruction fetch or store that needs stronger permission.

`map_pages` and `protect_pages` reject user-accessible writable+executable
flags as invalid mapping arguments before the mapping table changes.

## Faults

Failed translation or permission checks raise a synchronous page fault. The
faulting instruction does not retire.

Current trap fields for VM-backed instruction-fetch/load/store faults:

```text
trap_cause  instruction fetch fault, load fault, or store fault
trap_pc     faulting instruction PC
trap_value  faulting virtual address
trap_arg0   interrupted address mode: 0 = physical, 1 = translated
trap_arg1   interrupted privilege mode: 0 = kernel, 1 = user
trap_arg2   0
```

If no trap vector is installed, the VM reports a hard CPU trap to the host, as
with other synchronous CPU exceptions. If a trap vector is installed, the CPU
enters it in physical mode.

Invalid MMU mappings are guest faults, not host panics. Examples include
unknown address-space handles, unaligned virtual or physical pages, physical
pages outside RAM, overlapping mappings, or attempted MMIO mappings.

## Kernel And Syscalls

The kernel owns all address-space maps. User processes cannot create, destroy,
map, unmap, or modify mappings directly.

For syscall arguments that are guest pointers:

1. The CPU enters the trap vector in physical mode.
2. The kernel reads the interrupted process address-space id from its process
   table or saved trap metadata.
3. The kernel translates and copies user buffers through `mmu0` copy commands.
4. Invalid user pointers return existing negative K16 `ERROR_FAULT` where the
   current syscall ABI expects recoverable pointer errors.
5. CPU instruction-fetch/load/store faults in translated user code enter the
   trap vector and terminate the current foreground child with `ERROR_FAULT`;
   the waiting parent resumes through the normal child-exit path.

This replaces the current physical `memory_start..memory_end` pointer check for
VM-enabled user processes. Physical process ranges may remain as an internal
allocator constraint for the kernel-selected backing pages.

## Loading Processes

Physical boot flow remains unchanged:

```text
host -> BIOS flash -> bootloader -> kernel in physical mode -> translated init
```

The VM-enabled production user launch does this for `/bin/init.kx`,
`/bin/shell.kx`, and shell-started utilities:

1. Load a dynamic K16E `program` image into kernel-selected allocator-backed
   physical pages.
2. Use the process arena for `.bss`, heap, optional argv, and stack.
3. Reserve a small physical kernel trap stack outside user memory: for init,
   the top 4 KiB page below `BootInfo.ram_size`; for children, below the
   saved parent stack.
4. For init, choose the lower user arena boundary from the next 4 KiB page
   after the maximum of `BootInfo.program_base`, storage loader scratch, linked
   kernel image end, and kernel terminal state. For translated children, reuse
   the caller's user virtual arena bounds in a new address space instead of
   carving a child virtual arena out of the caller's live physical stack.
5. Create a host MMU address space and map the loaded image pages plus the
   selected committed user stack pages into it.
6. Restore the entry register frame, including argv registers for child runs.
7. Enter user-translated mode at the dynamic image entry PC.
8. Set user `sp` to the selected process stack top and the trap stack to the
   reserved physical kernel stack.

The production mapping is not a fixed physical window: user virtual addresses
come from the process arena, while physical backing pages come from the kernel
page-frame allocator. At launch, only the loaded image pages and two 4 KiB user
stack pages below the selected stack top are committed. The heap limit remains
below a 4 KiB guard under that stack range. Heap pages are committed later by
`BRK`/`SBRK` when the break crosses a previously unmapped 4 KiB VM page
boundary; those pages are mapped user-writable and non-executable. This gives
the host VM a real address-space id, permission checks, trap-mode separation,
and `mmu0` copy-helper boundary without guest-visible page tables.

## Implementation Sequence

The implementation sequence is:

1. Add a VM-internal address translation module and tests while leaving
   physical mode as the default and only active runtime mode. Done.
2. Add host-managed address-space map operations in a versioned CPU/MMU ABI
   slice, including command-based user-mode activation. Done.
3. Add kernel-owned address-space construction for one child process while init
   can remain physical. Done for shell and shell-launched utilities at the
   current foreground depth.
4. Convert syscall user-buffer validation from physical range checks to the
   implemented `mmu0` copy helpers for VM-enabled processes. Done.
5. Move init into a VM-enabled user process after the shell and child path is
   stable. Done for bundled `/bin/init.kx`.
6. Enable nested translated `RUN` by adding a translated-parent trap-return
   path instead of only the physical-parent return override. Done for the
   current init -> shell -> utility foreground depth; a general process
   lifecycle remains pending.

Each slice should keep existing physical boot and storage behavior working.

## Compatibility Notes

- Existing physical-mode K16E bootloader and kernel artifacts remain valid.
- Existing dynamic user programs remain the preferred executable format for OS
  launches.
- Existing syscall numbers do not need to change merely because user pointers
  become virtual; the kernel changes how it validates and copies them.
- Snapshot and suspend/resume formats will need an explicit extension before
  VM-enabled process state can be persisted.
