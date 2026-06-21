# K16 Object v1 Relocatable Object ABI

## Status

Status: experimental.

K16 relocatable objects use ELF32 little-endian `ET_REL` files. This is the
first object format accepted by the LLVM-facing K16 toolchain. LLVM must emit
relocatable objects, not `K16E`; K16 tooling links those objects into final
`K16E` bootloader, kernel, standalone program, or dynamic user program images.

This boundary keeps the VM independent from LLVM and from object-file details.
The VM must not parse ELF, read relocation records, resolve symbols, or know
whether code came from LLVM, Rux source, or handwritten assembly. ELF parsing,
symbol resolution, relocation application, and final `K16E` emission belong to
compiler and linker tooling outside the VM.

## Relationship To K16E

The static pipeline is:

```text
LLVM backend or handwritten K16 object fixtures
  -> K16 ELF32 ET_REL object
  -> K16 linker
  -> K16E fixed image or dynamic user program
  -> storage media or guest exec service
  -> VM loader
```

`K16E` remains the guest-loadable executable container. A loader must reject an
ELF object presented as `K16E`, and the linker must reject object features it
does not implement. Unsupported relocations are link-time errors.

The current tool entry point is:

```text
k16 link --target <bios|boot|kernel|program|program-dynamic|shared-object> [--shared-cpu-helpers] [--import <library>:<symbol>] [--map <output.map>] <input.ko>... -o <output>
```

The command accepts K16 ELF32 `ET_REL` inputs, resolves static symbols,
applies supported relocations, and emits a validated fixed-image `K16E` for
bootloader, kernel, and standalone program targets. The `program-dynamic`
target emits a K16E v2 dynamic user program with base-relative payload
addresses and loader-applied relocation metadata. With `--shared-cpu-helpers`,
`program-dynamic` emits a K16E v3 dynamic user program with CPU helper runtime
requirement metadata and CPU helper relocation records. With one or more
`--import <library>:<symbol>` records, `program-dynamic` emits a K16E v5
dynamic user program with `NEEDED` library metadata and import relocation
records for those symbols. Other unresolved symbols remain link-time errors.
The `shared-object` target emits a K16E v4 shared object, does not require
`_start`, and exports retained global definitions as base-relative shared
object offsets. The `bios` target emits raw BIOS flash bytes and prefixes them
with a reset-address trampoline that initializes `sp` to the current fixed 192
KiB stack top and jumps to `_start`. When `--map` is present, the linker writes
a deterministic retained-section report beside the linked output without
changing the emitted executable bytes.

Rust `bin` crates use the linker-driver entry point:

```text
k16-ld <rustc linker args> --k16-target <bios|boot|kernel|program|program-dynamic|shared-object> [--k16-import <library>:<symbol>] [--map <output.map>] -o <output>
```

The driver consumes rustc-style object and archive arguments, extracts K16 ELF
members from `.rlib` archives only when they resolve currently undefined global
symbols, and then delegates to the same object linker. The target is explicit;
missing `--k16-target` is a hard error. When `--map` is present, the driver
writes the same retained-section report as `k16 link --map`.

`--k16-import <library>:<symbol>` is the linker-driver spelling for the same
import metadata emitted by `k16 link --import`.

The archive selection model follows the usual static-linker shape: object files
on the command line are included directly, while archive members are selected in
link order only when they resolve currently undefined global symbols. Symbols
declared with `--import` or `--k16-import` are not archive-selection roots; they
remain external imports in the K16E v5 output. If two
archives can define the same helper, the first selected provider satisfies the
symbol and later duplicate providers are not pulled.

Bundled Rust guest artifacts use this driver directly:

```text
rust/guest/k16-bios   -> k16-ld --k16-target=bios   -> firmware/k16-bios.kflash
rust/guest/k16-boot   -> k16-ld --k16-target=boot   -> kernel-loader.kb
rust/guest/k16-kernel -> k16-ld --k16-target=kernel -> display-ok.kx
```

These builds are freestanding Rust `bin` crates. Gradle resolves a prepared K16
toolchain through `prepareK16Toolchain`, then invokes Cargo with `RUSTC` and
K16 linker flags pointed at that prepared install. Missing toolchain state is a
hard configuration error. There is no fallback to host rustc, host linker
behavior, or retired Rux source generation.

Source-built prepared toolchains must include the stage1 K16 rustc, `k16-ld`,
Rust source for `-Zbuild-std=core`, and the matching host `library/std` sysroot
artifacts. The host std artifacts are required because Cargo compiles build
scripts for the host while it builds freestanding K16 `core`.

## Freestanding Runtime Boundary

The first freestanding startup object is generated with:

```text
k16 runtime k16-startup [--target <program|program-dynamic>] -o <startup.ko>
k16 runtime k16-memory-helpers -o <helpers.ko>
k16 runtime k16-cpu-helpers -o <cpu-helpers.ko>
```

The startup object defines `_start` and requires an application-defined `main`.
The linker uses `_start` as the final `K16E` entry symbol. At runtime `_start`
initializes `sp` to the program stack top, calls `main`, passes the returned
`r0` value to the K16 `EXIT` syscall as the process status, and keeps a
trailing `halt` instruction as a fail-closed boundary if a broken kernel
returns from `EXIT`. For `--target program-dynamic`, startup assumes the kernel
has already installed the selected process stack top in `r15` before entering
`_start`; it must not bake a fixed physical stack top into the artifact.

The memory and integer helper object is built from the guest Rust `#![no_core]`
runtime source at `rust/guest/k16-rt/src/no_core_helpers.rs`. Building it requires
`K16_RUSTC` to point at the custom rustc that contains the K16 LLVM target and
`K16_LLVM_BIN_DIR` to point at the K16 LLVM tools used to lower the generated
LLVM IR into an ELF object. `K16_RUST_TARGET_JSON` can override the target spec;
otherwise the repo target spec at `tools/k16-unknown-kraftos.json` is used.

The CPU helper object is generated by the K16 host tool and defines the small
K16 platform helpers used by `k16-rt` for halt, CSR access, and interrupt
return. These helpers are explicit link inputs; the VM and linker do not
synthesize them.

Cargo-built Rust `bin` crates use the normal `core` inputs produced by
`-Zbuild-std=core`. The standalone K16 runtime path links explicit runtime
objects instead of asking the object linker to synthesize platform symbols:
`k16-memory-helpers` owns the small C ABI memory helpers and the current i64/u64
compiler-rt helpers, while `k16-cpu-helpers` owns K16 CPU control helpers such
as halt, wait, yield, CSR access, syscalls, and interrupt return.

Runtime helper symbol names:

```text
`_start`          provided by k16-startup
`main`            required application entry called by _start
`memcpy`          provided by k16-memory-helpers
`memset`          provided by k16-memory-helpers
`memmove`         provided by k16-memory-helpers
`__k16_memcpy`  provided by k16-memory-helpers
`__k16_memset`  provided by k16-memory-helpers
`__k16_memmove` provided by k16-memory-helpers
`__divdi3`       provided by k16-memory-helpers
`__udivdi3`      provided by k16-memory-helpers
`__moddi3`       provided by k16-memory-helpers
`__umoddi3`      provided by k16-memory-helpers
`__ashldi3`      provided by k16-memory-helpers
`__lshrdi3`      provided by k16-memory-helpers
`__ashrdi3`      provided by k16-memory-helpers
`__k16_halt_once`              provided by k16-cpu-helpers
`__k16_wait_once`              provided by k16-cpu-helpers
`__k16_yield_once`             provided by k16-cpu-helpers
`__k16_iret_once`              provided by k16-cpu-helpers
`__k16_save_trap_frame`        provided by k16-cpu-helpers
`__k16_restore_trap_frame`     provided by k16-cpu-helpers
`__k16_write_trap_vector`      provided by k16-cpu-helpers
`__k16_read_trap_cause`        provided by k16-cpu-helpers
`__k16_read_trap_pc`           provided by k16-cpu-helpers
`__k16_read_trap_value`        provided by k16-cpu-helpers
`__k16_read_trap_arg0`         provided by k16-cpu-helpers
`__k16_read_trap_arg1`         provided by k16-cpu-helpers
`__k16_read_trap_arg2`         provided by k16-cpu-helpers
`__k16_syscall_once`           provided by k16-cpu-helpers
`__k16_syscall0`               provided by k16-cpu-helpers
`__k16_syscall1`               provided by k16-cpu-helpers
`__k16_syscall3`               provided by k16-cpu-helpers
`__k16_iret_with_r0`           provided by k16-cpu-helpers
`__k16_write_interrupt_enable` provided by k16-cpu-helpers
`__k16_write_interrupt_mask`   provided by k16-cpu-helpers
`__k16_read_interrupt_pending` provided by k16-cpu-helpers
```

`__k16_save_trap_frame` writes the current saved CPU trap frame into the
`k16_rt::TrapFrame` layout: `r0..r15`, `resume_pc`, `stack_pointer`, and
`interrupt_enable`. `__k16_restore_trap_frame` restores `r1..r15` plus the
resume fields and returns the saved `r0`; kernel code passes that return value
to `__k16_iret_with_r0` when entering the restored context.

Missing helper symbols are link-time errors. The linker must not synthesize
helper bodies, fall back to VM hooks, or ask the VM to resolve runtime helpers.
Callers link helper support by passing the Rust-built helper object as an
ordinary `k16 link` input beside startup and application objects. The helper
object is not implicit.

## Shared CPU Helper Format Boundary

K16E v3 is the first executable format extension for kernel-known runtime
requirements. It is enabled only by `k16 link --target program-dynamic
--shared-cpu-helpers`.

This metadata declares a dependency on the K16 CPU helper runtime ABI version
and helper table version. Relocations against known `k16-cpu-helpers` symbols
become CPU helper relocation records keyed by fixed helper ids. Other unresolved
symbols remain link-time errors.

This is runtime requirement metadata, not a shared-library ABI. It does not
enable dynamic symbol lookup, arbitrary imports or exports, GOT/PLT, TLS,
constructors, destructors, shared writable data, or user-defined shared
objects. It also does not load or map the helper runtime artifact; that remains
kernel-loader behavior for a later ABI slice. Static-helper programs remain
valid and continue linking `k16-cpu-helpers.o` as an explicit object.

## ELF Identification

K16 object files use these ELF header values:

```text
EI_CLASS      ELFCLASS32
EI_DATA       ELFDATA2LSB
EI_VERSION    EV_CURRENT
e_type        ET_REL
e_machine     0x5258
e_version     EV_CURRENT
```

`e_machine = 0x5258` is the experimental K16 machine value for this ABI
revision. K16 tooling must reject other machine values when linking K16
objects. If K16 later receives an officially assigned ELF machine value, that
change requires a new object ABI revision.

Section headers and symbol tables use ordinary ELF32 little-endian layout.
There is no dynamic section, program header requirement, interpreter, shared
object mode, or loader-side relocation in v1.

## Sections

The first linker consumes these allocatable sections:

```text
name              type         flags                         alignment
.text.k16*      SHT_PROGBITS SHF_ALLOC | SHF_EXECINSTR      2
.rodata           SHT_PROGBITS SHF_ALLOC                      4
.data             SHT_PROGBITS SHF_ALLOC | SHF_WRITE          4
.bss              SHT_NOBITS   SHF_ALLOC | SHF_WRITE          4
.k16.attributes SHT_PROGBITS                              1
```

`SHF_ALLOC` marks sections that contribute bytes or zero-fill space to the
linked image. `SHT_NOBITS` is allowed only for `.bss`; the linker assigns RAM
space for it but does not copy bytes from the object file.

`.text.k16` and `.text.k16.<symbol>` contain K16 instruction bytes and must
have even size.
`.rodata` and `.data` contain initialized bytes. `.bss` contributes zero-filled
memory. `.k16.attributes` is non-allocatable metadata for toolchain checks.

Unknown non-allocatable sections may be ignored only if they have no relocations
that affect allocated output. Unknown `SHF_ALLOC` sections are unsupported in
v1 and must be rejected instead of guessed into the output image.

## Symbols

Symbols use the normal ELF symbol table.

- Defined function symbols point into `.text.k16*` sections and must be
  2-byte aligned.
- Defined object symbols point into `.rodata`, `.data`, or `.bss`.
- Undefined symbols must be resolved by another object or by an explicit runtime
  object supplied to the linker.
- `STT_FILE` and other `SHN_ABS` metadata symbols do not define output payload
  addresses and are ignored by the linker when building the global symbol map.
- Weak symbols, common symbols, thread-local symbols, dynamic symbols, and
  symbol versioning are unsupported in v1.

The final `K16E` entry point is selected by the linker profile. Program images
use `_start` unless the command line explicitly chooses another defined symbol.
Bootloader and kernel profiles may use their profile-specific entry symbol, but
the resolved entry address must still satisfy the `K16E` entry validation rules.
The current linker profile bases are fixed: bootloader at `0x00000800`, kernel
at `0x00004000`, and first user program at `0x00015000`. Bootloader and kernel
payloads are not capped by neighboring fixed-image load bases. Program and
dynamic-program payloads must still fit below the current program stack top
selected by the profile.

## Relocations

K16 objects use ELF32 RELA relocation sections. The addend lives in the
relocation record, not in the bytes being patched.

Relocation expression terms:

```text
S  resolved symbol address
A  signed relocation addend
P  address of the relocated field
```

Supported relocation kinds:

```text
value  name                field                         calculation
0      R_K16_NONE        none                          no operation
1      R_K16_ABS32       little-endian u32             S + A
2      R_K16_CALL32      imm32 low/high extension      S + A
3      R_K16_BRANCH4     branch low-nibble offset      ((S + A) - (P + 2)) / 2
```

`R_K16_ABS32` is used for absolute data references and for ordinary 32-bit
address constants. The linker must reject the relocation if `S + A` does not
fit in `u32`.

`R_K16_CALL32` applies to the immediate payload of a canonical call
materialization sequence:

```text
imm32 scratch, target
call scratch
```

It writes the same 32-bit value as `R_K16_ABS32`, but the distinct relocation
kind lets the linker diagnose call-specific placement or relaxation rules in a
future ABI. v1 linkers must not invent a direct-call encoding.

`R_K16_BRANCH4` patches the low nibble of `branch_if_zero` or
`branch_if_nonzero`. The computed word offset must be an integer in `-8..=7`;
the linker must reject out-of-range branches. The high twelve bits of the
instruction word must already contain the intended branch opcode and source
register, and the linker must preserve those bits.

Relocations may target allocated sections or undefined symbols that resolve to
allocated sections. Relocations against discarded, debug-only, dynamic, TLS, or
unsupported section kinds are link-time errors.

## Linking Rules

The v1 linker is static only:

1. Validate every input object as K16 ELF32 `ET_REL`.
2. Start section reachability from `_start`.
3. Follow relocations from retained allocatable sections and retain only the
   sections they reference.
4. Resolve strong symbols used by retained sections and report duplicate strong
   definitions.
5. Lay out retained `.text.k16*`, `.rodata`, `.data`, and `.bss` sections in
   the selected target profile address space.
6. Apply supported RELA relocations from retained sections.
7. Emit a single-load-section `K16E` image for the selected ABI kind. The
   emitted `file_size` covers initialized payload bytes, while `memory_size`
   covers initialized bytes plus any trailing `.bss` zero-fill tail.

The optional link map reports only retained allocated sections after step 3.
Each section row includes output offset, section class, initialized file bytes,
runtime memory bytes, source object/member name, and section name. Unreachable
allocated sections are omitted from the map for the same reason they are
omitted from the executable payload.

For `K16E` output, `.bss` must not be serialized as object-file bytes when it
is only trailing zero-filled memory. The linker represents it by increasing the
K16E section `memory_size`; loaders copy `file_size` bytes and zero-fill the
remaining memory range.

## Unsupported Features

The v1 object ABI rejects:

- ELF64 and big-endian ELF;
- `ET_EXEC`, `ET_DYN`, archives as direct linker input, and shared libraries;
- program headers as semantic input;
- dynamic linking and loader-side relocation;
- GOT, PLT, PIC, TLS, exceptions, unwind tables, and debug relocation
  semantics;
- mergeable allocated sections, COMDAT groups, common symbols, and weak
  resolution semantics;
- relocation kinds other than `R_K16_NONE`, `R_K16_ABS32`,
  `R_K16_CALL32`, and `R_K16_BRANCH4`.

Unsupported features must fail before final `K16E` emission. The linker must
not fall back to raw K16 bytes, reinterpret unsupported sections as known
sections, or ask the VM to resolve anything at runtime.
