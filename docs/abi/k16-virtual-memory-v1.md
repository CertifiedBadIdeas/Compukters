# K16 Virtual Memory v1

> Issue: [#263](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/263)

## Status

Status: partially implemented design.

This document defines the first intended K16 virtual-memory and process
address-space contract. The current VM implements the host MMU map, CPU
address/privilege modes, privileged `mmu0` map controls, and trap/`iret` mode
switching. The guest kernel, bootloader, and bundled userland still use the
physical-memory process model until later slices migrate process loading and
syscall user-buffer handling.

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

`k16-cpu-v1.md` remains physical-memory-only. It already states that privilege
levels, virtual memory, and page-table translation are later ABI slices. Its
4-bit CSR namespace is currently full, so MMU controls must be introduced by a
future CPU ABI revision or an explicit host MMU control device. They must not
be silently retrofitted into unused v1 behavior.

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
profile uses a 148 KiB RAM size, which is exactly 37 VM pages. A 4 KiB page
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
activate_user_address_space(address_space_id, entry_pc, stack_pointer)
copy_from_user(address_space_id, user_virtual_addr, kernel_physical_addr, byte_count)
copy_to_user(address_space_id, user_virtual_addr, kernel_physical_addr, byte_count)
```

`activate_user_address_space` switches the current K16 CPU to translated user
execution. The command device itself does not own address spaces; it records
guest commands, and `ComputerMachine` applies them to its host-managed MMU
registry before guest execution continues. User translation is host-enforced,
and the kernel configures mappings instead of publishing raw page-table memory
for the host to walk.

Address-space destruction and unmapping are intentionally left for a later
lifecycle slice. The first control boundary is enough to construct mappings and
enter one translated user context without changing physical-mode boot. The copy
commands let physical/kernel syscall handlers move bytes across the user/kernel
boundary without directly dereferencing user virtual pointers.

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

## Faults

Failed translation or permission checks raise a synchronous page fault. The
faulting instruction does not retire.

Planned trap fields for the MMU ABI slice:

```text
trap_cause  page fault cause value assigned by the CPU ABI revision
trap_pc     faulting instruction PC
trap_value  faulting virtual address
trap_arg0   access kind: 1 = fetch, 2 = load, 3 = store
trap_arg1   fault reason: 1 = not present, 2 = permission, 3 = invalid mapping
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
3. Create a host MMU address-space map for those pages.
4. Enter user-translated mode at `user_image_base + entry_offset`.
5. Set user `sp` to the selected virtual stack top.

The dynamic image relocation base becomes the selected virtual image base, not
the physical load base. This lets user pointers remain stable while physical
backing pages are chosen by the kernel.

## Implementation Sequence

The intended follow-up slices are:

1. Add a VM-internal address translation module and tests while leaving
   physical mode as the default and only active runtime mode.
2. Add host-managed address-space map operations in a versioned CPU/MMU ABI
   slice, including command-based user-mode activation.
3. Add kernel-owned address-space construction for one child process while init
   can remain physical.
4. Convert syscall user-buffer validation from physical range checks to the
   implemented `mmu0` copy helpers for VM-enabled processes.
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
